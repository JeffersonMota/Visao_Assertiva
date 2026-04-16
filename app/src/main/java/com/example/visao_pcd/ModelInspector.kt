package com.example.visao_pcd

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil

object ModelInspector {
    fun inspect(context: Context, modelPath: String) {
        try {
            val modelFile = FileUtil.loadMappedFile(context, modelPath)
            val interpreter = Interpreter(modelFile)
            
            val inputCount = interpreter.inputTensorCount
            Log.d("ModelInspector", "Model: $modelPath")
            for (i in 0 until inputCount) {
                val tensor = interpreter.getInputTensor(i)
                Log.d("ModelInspector", "Input $i: name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
            }
            
            val outputCount = interpreter.outputTensorCount
            for (i in 0 until outputCount) {
                val tensor = interpreter.getOutputTensor(i)
                Log.d("ModelInspector", "Output $i: name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
            }
            
            interpreter.close()
        } catch (e: Exception) {
            Log.e("ModelInspector", "Error inspecting model", e)
        }
    }
}
