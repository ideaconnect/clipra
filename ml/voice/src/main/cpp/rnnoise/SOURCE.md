# RNNoise source

From xiph's official RNNoise (https://github.com/xiph/rnnoise), BSD-3-Clause (see `COPYING`):

- The v0.2 release, `rnnoise-0.2.tar.gz` from the GitHub release (SHA-256
  `90fce4b00b9ff24c08dbfe31b82ffd43bae383d85c5535676d28b0a2b11c0d37`), which includes the
  trained weights in `src/rnnoise_data.c`.
- `src/common.h`, `src/vec.h`, `src/vec_avx.h`, `src/vec_neon.h` and `src/x86/x86cpu.h` from
  upstream commit `372f7b4b76cde4ca1ec4605353dd17898a99de38` ("Fix compilation errors", the day
  after v0.2), without which the ARM build fails.

Left out: the examples, the training scripts, `dump_features.c`, `write_weights.c` and the x86
run-time dispatch sources. Clipra builds the rest with `../CMakeLists.txt`.
