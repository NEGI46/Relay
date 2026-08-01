#!/bin/bash -eu

cd "$SRC/relay"
./gradlew :fuzz-jvm:prepareClusterFuzzLite --no-daemon --console=plain
cp -a fuzz-jvm/build/clusterfuzzlite-runtime/. "$OUT/"

for target in QrFrameFuzzer GatewayMessageFuzzer; do
  target_class="com.example.relay.fuzz.$target"
  cat > "$OUT/$target" <<EOF
#!/bin/bash
# LLVMFuzzerTestOneInput-compatible launcher for ClusterFuzzLite.
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="\$JVM_LD_LIBRARY_PATH":\$this_dir \
"\$this_dir/jazzer_driver" \
  --agent_path="\$this_dir/jazzer_agent_deploy.jar" \
  --cp="\$this_dir" \
  --target_class="$target_class" \
  --jvm_args="-Xmx2048m:-Djava.awt.headless=true" \
  "\$@"
EOF
  chmod +x "$OUT/$target"
done
