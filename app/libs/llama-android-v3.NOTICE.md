# llama.cpp Android binding

`llama-android-v3.aar` is built from the official `ggml-org/llama.cpp` Android
example at commit `4fc4ec5`.

Local compatibility changes:

- ARM64 only and Android minSdk 26.
- Kotlin 1.9.24 compatibility.
- Generation cancellation without unloading the model, for V3 voice barge-in.
- Android API 26 logging fallback.

llama.cpp is distributed under the MIT license:
https://github.com/ggml-org/llama.cpp/blob/master/LICENSE
