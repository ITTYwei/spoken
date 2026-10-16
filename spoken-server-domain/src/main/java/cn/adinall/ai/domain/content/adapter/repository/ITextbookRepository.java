package cn.adinall.ai.domain.content.adapter.repository;

import cn.adinall.ai.domain.content.model.valobj.TextbookVO;
import java.util.List;

/** 教材定义仓储契约；基础设施负责读取随应用发布的文本资源，领域不解析文件。 */
public interface ITextbookRepository {
    /** 返回不可变教材目录；内容缺失应在启动加载时明确失败。 */
    List<TextbookVO> list();
}
