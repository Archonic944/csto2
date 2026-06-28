CC=/Users/gabriel/Development/Research/commons-csv
JAR=/Users/gabriel/Development/Research/csto2/target/csto2.jar
CP="$(cat /private/tmp/claude-501/-Users-gabriel-Development-Research-csto2/2bf931a5-721a-47ea-9c35-5d3d1880425d/scratchpad/cc.full.cp)"
ORDER=/private/tmp/claude-501/-Users-gabriel-Development-Research-csto2/2bf931a5-721a-47ea-9c35-5d3d1880425d/scratchpad/cc-out/select/alloc-front.order

cd "$CC" && CSTO_TRACE_PROGRESS=1 java -cp "$CP:$JAR" com.csto2.trace.TraceRunner \
  --order "$ORDER" --order-id alloc-front --out /tmp/alloc-front-run.jsonl
