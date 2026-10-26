package com.ragqa.rag.retrieval;

import com.ragqa.rag.chat.TaskDeadline;
import java.util.List;

/** 只负责把文本转向量，不负责权限、索引、召回或回答；实现可替换但不得静默伪造向量。 */
public interface TextEmbeddings {
    record Vector(String model, List<Double> values) {
        public Vector { values = List.copyOf(values); }
    }
    List<Vector> embed(List<String> texts, TaskDeadline deadline);
}
