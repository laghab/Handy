// Vendored from k2-fsa/sherpa-onnx v2.0.1 (Apache-2.0). Do not rename: JNI binds by class/field name.
package com.k2fsa.sherpa.onnx

data class HomophoneReplacerConfig(
    var dictDir: String = "", // unused
    var lexicon: String = "",
    var ruleFsts: String = "",
)
