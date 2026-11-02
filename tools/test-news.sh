#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/news-tests
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d out/news-tests \
  app/src/io/freemytv/News.java tests/NewsTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp out/news-tests io.freemytv.NewsTest
