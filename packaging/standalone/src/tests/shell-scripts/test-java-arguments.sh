#!/usr/bin/env bash

test_description="Test Java arguments"

. ./lib/sharness.sh
fake_install

run_console() {
  neo4j-home/bin/neo4j console
}

run_daemon() {
  FAKE_JAVA_SLEEP=1 neo4j-home/bin/neo4j start &&
  neo4j-home/bin/neo4j stop
}

for run_command in run_console run_daemon; do
  test_expect_success "should specify -server" "
    ${run_command} &&
    test_expect_java_arg '-server'
  "

  test_expect_success "should disable explicit GC" "
    ${run_command} &&
    test_expect_java_arg '-XX:+DisableExplicitGC'
  "

  test_expect_success "should add additional options" "
    set_config 'wrapper.java.additional' '-XX:+UseG1GC', neo4j-wrapper.conf &&
    ${run_command} &&
    test_expect_java_arg '-XX:+UseG1GC'
  "

  test_expect_success "should set heap size constraints" "
    set_config 'wrapper.java.initmemory' '512' neo4j-wrapper.conf &&
    set_config 'wrapper.java.maxmemory' '1024' neo4j-wrapper.conf &&
    ${run_command} &&
    test_expect_java_arg '-Xms512m' &&
    test_expect_java_arg '-Xmx1024m'
  "

  test_expect_success "should set neo4j.home" "
    ${run_command} &&
    test_expect_java_arg '-Dneo4j.home=$(neo4j_home)'
  "

  test_expect_success "should invoke main class" "
    set_main_class some.main.class &&
    ${run_command} &&
    test_expect_java_arg 'some.main.class'
  "

  test_expect_success "should set default charset to UTF-8" "
    ${run_command} &&
    test_expect_java_arg '-Dfile.encoding=UTF-8'
  "

  test_expect_success "should add lib dirs to classpath" "
    ${run_command} &&
    test_expect_java_arg '-cp $(neo4j_home)/lib/*:$(neo4j_home)/system/lib/*'
  "

  test_expect_success "should add plugins to classpath" "
    ${run_command} &&
    test_expect_java_arg ':$(neo4j_home)/plugins/*'
  "
done

test_done
