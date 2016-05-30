/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.roles;

import java.io.IOException;
import java.util.List;

import org.neo4j.coreedge.raft.Followers;
import org.neo4j.coreedge.raft.RaftMessageHandler;
import org.neo4j.coreedge.raft.RaftMessages;
import org.neo4j.coreedge.raft.RaftMessages.Heartbeat;
import org.neo4j.coreedge.raft.outcome.Outcome;
import org.neo4j.coreedge.raft.outcome.ShipCommand;
import org.neo4j.coreedge.raft.replication.ReplicatedContent;
import org.neo4j.coreedge.raft.state.ReadableRaftState;
import org.neo4j.coreedge.raft.state.follower.FollowerState;
import org.neo4j.coreedge.raft.state.follower.FollowerStates;
import org.neo4j.helpers.collection.FilteringIterable;
import org.neo4j.logging.Log;

import static java.lang.Math.max;
import static org.neo4j.coreedge.raft.roles.Role.FOLLOWER;
import static org.neo4j.coreedge.raft.roles.Role.LEADER;

public class Leader implements RaftMessageHandler
{
    private static <MEMBER> Iterable<MEMBER> replicationTargets( final ReadableRaftState<MEMBER> ctx )
    {
        return new FilteringIterable<>( ctx.replicationMembers(), member -> !member.equals( ctx.myself() ) );
    }

    static <MEMBER> void sendHeartbeats( ReadableRaftState<MEMBER> ctx, Outcome<MEMBER> outcome ) throws IOException
    {
        long commitIndex = ctx.leaderCommit();
        long commitIndexTerm = ctx.entryLog().readEntryTerm( commitIndex );
        Heartbeat<MEMBER> heartbeat = new Heartbeat<>( ctx.myself(), ctx.term(), commitIndex, commitIndexTerm );
        for ( MEMBER to : replicationTargets( ctx ) )
        {
            outcome.addOutgoingMessage( new RaftMessages.Directed<>( to, heartbeat ) );
        }
    }

    @Override
    public <MEMBER> Outcome<MEMBER> handle( RaftMessages.RaftMessage<MEMBER> message,
                                            ReadableRaftState<MEMBER> ctx, Log log ) throws IOException
    {
        Outcome<MEMBER> outcome = new Outcome<>( LEADER, ctx );

        switch ( message.type() )
        {
            case HEARTBEAT:
            {
                Heartbeat<MEMBER> req = (Heartbeat<MEMBER>) message;

                if ( req.leaderTerm() < ctx.term() )
                {
                    break;
                }

                outcome.steppingDown();
                outcome.setNextRole( FOLLOWER );
                Heart.beat( ctx, outcome, (Heartbeat<MEMBER>) message );
                break;
            }

            case HEARTBEAT_TIMEOUT:
            {
                sendHeartbeats( ctx, outcome );
                break;
            }

            case APPEND_ENTRIES_REQUEST:
            {
                RaftMessages.AppendEntries.Request<MEMBER> req = (RaftMessages.AppendEntries.Request<MEMBER>) message;

                if ( req.leaderTerm() < ctx.term() )
                {
                    RaftMessages.AppendEntries.Response<MEMBER> appendResponse =
                            new RaftMessages.AppendEntries.Response<>( ctx.myself(), ctx.term(), false, req.prevLogIndex(),
                                    ctx.entryLog().appendIndex() );

                    outcome.addOutgoingMessage( new RaftMessages.Directed<>( req.from(), appendResponse ) );
                    break;
                }
                else if ( req.leaderTerm() == ctx.term() )
                {
                    throw new IllegalStateException( "Two leaders in the same term." );
                }
                else
                {
                    // There is a new leader in a later term, we should revert to follower. (§5.1)
                    outcome.steppingDown();
                    outcome.setNextRole( FOLLOWER );
                    Appending.handleAppendEntriesRequest( ctx, outcome, req );
                    break;
                }
            }

            case APPEND_ENTRIES_RESPONSE:
            {
                RaftMessages.AppendEntries.Response<MEMBER> response =
                        (RaftMessages.AppendEntries.Response<MEMBER>) message;

                if ( response.term() < ctx.term() )
                {
                    /* Ignore responses from old terms! */
                    break;
                }
                else if ( response.term() > ctx.term() )
                {
                    outcome.setNextTerm( response.term() );
                    outcome.steppingDown();
                    outcome.setNextRole( FOLLOWER );
                    outcome.replaceFollowerStates( new FollowerStates<>() );
                    break;
                }

                FollowerState follower = ctx.followerStates().get( response.from() );

                if ( response.success() )
                {
                    assert response.matchIndex() <= ctx.entryLog().appendIndex();

                    boolean followerProgressed = response.matchIndex() > follower.getMatchIndex();

                    outcome.replaceFollowerStates( outcome.getFollowerStates()
                            .onSuccessResponse( response.from(), max( response.matchIndex(), follower.getMatchIndex() ) ) );

                    outcome.addShipCommand( new ShipCommand.Match( response.matchIndex(), response.from() ) );

                    /*
                     * Matches from older terms can in complicated leadership change / log truncation scenarios
                     * be overwritten, even if they were replicated to a majority of instances. Thus we must only
                     * consider matches from this leader's term when figuring out which have been safely replicated
                     * and are ready for commit.
                     * This is explained nicely in Figure 3.7 of the thesis
                     */
                    boolean matchInCurrentTerm = ctx.entryLog().readEntryTerm( response.matchIndex() ) == ctx.term();

                    /*
                     * The quorum situation may have changed only if the follower actually progressed.
                     */
                    if ( followerProgressed && matchInCurrentTerm )
                    {
                        // TODO: Test that mismatch between voting and participating members affects commit outcome

                        long quorumAppendIndex =
                                Followers.quorumAppendIndex( ctx.votingMembers(), outcome.getFollowerStates() );
                        if ( quorumAppendIndex > ctx.commitIndex() )
                        {
                            outcome.setLeaderCommit( quorumAppendIndex );
                            outcome.setCommitIndex( quorumAppendIndex );
                            outcome.addShipCommand( new ShipCommand.CommitUpdate() );
                        }
                    }
                }
                else // Response indicated failure.
                {
                    if ( response.appendIndex() >= ctx.entryLog().prevIndex() )
                    {
                        // Signal a mismatch to the log shipper, which will serve an earlier entry.
                        outcome.addShipCommand( new ShipCommand.Mismatch( response.appendIndex(), response.from() ) );
                    }
                    else
                    {
                        // There are no earlier entries, message the follower that we have compacted so that
                        // it can take appropriate action.
                        outcome.addOutgoingMessage( new RaftMessages.Directed<>( response.from(),
                                new RaftMessages.LogCompactionInfo<>( ctx.myself(), ctx.term(),
                                        ctx.entryLog().prevIndex() ) ) );
                    }
                }
                break;
            }

            case VOTE_REQUEST:
            {
                RaftMessages.Vote.Request<MEMBER> req = (RaftMessages.Vote.Request<MEMBER>) message;

                if ( req.term() > ctx.term() )
                {
                    outcome.steppingDown();
                    outcome.setNextRole( FOLLOWER );
                    Voting.handleVoteRequest( ctx, outcome, req );
                    break;
                }

                outcome.addOutgoingMessage( new RaftMessages.Directed<>( req.from(),
                        new RaftMessages.Vote.Response<>( ctx.myself(), ctx.term(), false ) ) );
                break;
            }

            case NEW_ENTRY_REQUEST:
            {
                RaftMessages.NewEntry.Request<MEMBER> req = (RaftMessages.NewEntry.Request<MEMBER>) message;
                ReplicatedContent content = req.content();
                Appending.appendNewEntry( ctx, outcome, content );
                break;
            }

            case NEW_BATCH_REQUEST:
            {
                RaftMessages.NewEntry.Batch<MEMBER> req = (RaftMessages.NewEntry.Batch<MEMBER>) message;
                List<ReplicatedContent> contents = req.contents();
                Appending.appendNewEntries( ctx, outcome, contents );
                break;
            }
        }

        return outcome;
    }
}