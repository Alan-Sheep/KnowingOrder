package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档数据访问层。
 */
@Mapper
public interface RagDocumentMapper extends BaseMapper<RagDocument> {
}
