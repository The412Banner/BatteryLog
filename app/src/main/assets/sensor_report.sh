echo "== shell"
id 2>&1
echo "== device"
for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.soc.manufacturer ro.soc.model ro.board.platform ro.hardware; do
  echo "$p=$(getprop $p)"
done
echo "== thermal zones (zone | type | temp)"
for z in /sys/class/thermal/thermal_zone*; do
  echo "${z##*/} | $(cat $z/type 2>&1) | $(cat $z/temp 2>&1)"
done
echo "== thermalservice"
dumpsys thermalservice 2>&1 | grep -E 'Temperature\{|temperatures|HAL|Thermal Status' | head -200
echo "== kgsl-3d0"
for f in gpu_busy_percentage gpubusy gpuclk clock_mhz max_gpuclk gpu_model devfreq/cur_freq devfreq/gpu_load; do
  echo "$f: $(head -c 200 /sys/class/kgsl/kgsl-3d0/$f 2>&1 | head -1)"
done
echo "== /sys/kernel/gpu"
for f in /sys/kernel/gpu/*; do
  if [ -f "$f" ]; then echo "${f##*/}: $(head -c 200 $f 2>&1 | head -1)"; fi
done
echo "== devfreq"
for d in /sys/class/devfreq/*; do
  echo "${d##*/}: cur_freq=$(cat $d/cur_freq 2>&1) load=$(head -1 $d/load 2>&1) gpu_load=$(head -1 $d/gpu_load 2>&1)"
done
echo "== power_supply/battery"
for f in current_now current_avg voltage_now temp status capacity charge_counter; do
  echo "$f: $(cat /sys/class/power_supply/battery/$f 2>&1)"
done
echo "== end"
