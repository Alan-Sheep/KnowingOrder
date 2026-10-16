package com.ragqa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ragqa.domain.entity.RagChunk;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档分块数据访问层。
 */
@Mapper
public interface RagChunkMapper extends BaseMapper<RagChunk> {
}
