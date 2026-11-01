#!/usr/bin/env bash
# Makes Hush's newer notification tones (res/raw/tone_*.ogg, Opus in Ogg) from plain sine waves: no samples, no licences.
# Each note: n(start, freq, decay) = a sine from `start` that fades out at `decay` per second, with soft overtones.
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/res/raw"

n() { echo "if(gte(t,$1),(sin(2*PI*$2*(t-$1))+0.25*sin(4*PI*$2*(t-$1))+0.08*sin(6*PI*$2*(t-$1)))*exp(-(t-$1)*$3),0)"; }

make() {  # name, seconds, gain, expression
    ffmpeg -v error -y -f lavfi -i "aevalsrc='$3*($4)':s=44100:d=$2" \
        -af "afade=t=in:d=0.005,afade=t=out:st=$(printf "%.2f" "$(echo "$2 - 0.15" | bc)"):d=0.15" \
        -ac 2 -ar 48000 -c:a libopus -b:a 64k "tone_$1.ogg"
}

make harp      1.2 0.22 "$(n 0 523 5)+$(n 0.12 659 5)+$(n 0.24 784 4)"
make marimba   1.0 0.30 "$(n 0 440 9)+0.2*sin(2*PI*1760*t)*exp(-t*30)+$(n 0.15 660 8)"
make dingdong  1.4 0.30 "$(n 0 659 3)+$(n 0.35 523 2.5)"
make bubble    0.8 0.30 "if(lt(t,0.15),sin(2*PI*(300*t+1500*t*t))*exp(-t*12),0)+if(gte(t,0.22)*lt(t,0.37),sin(2*PI*(400*(t-0.22)+1800*(t-0.22)^2))*exp(-(t-0.22)*12),0)"
make windchime 1.5 0.16 "$(n 0 1047 4)+$(n 0.08 1319 4)+$(n 0.2 1568 4)+$(n 0.33 1760 3.5)"
make lullaby   1.4 0.25 "$(n 0 784 3)+$(n 0.25 659 3)+$(n 0.5 523 2.5)"
make tick      0.5 0.35 "$(n 0 1200 40)+$(n 0.1 1200 40)"
make rise      1.0 0.28 "$(n 0 523 6)+$(n 0.18 784 5)"
make kalimba   1.1 0.28 "$(n 0 698 7)+0.15*sin(2*PI*3770*t)*exp(-t*25)+$(n 0.2 880 6)"
make bowl      1.6 0.30 "sin(2*PI*330*t)*exp(-t*1.8)+0.4*sin(2*PI*891*t)*exp(-t*2.5)+0.2*sin(2*PI*1650*t)*exp(-t*4)"
ls -la tone_*.ogg
