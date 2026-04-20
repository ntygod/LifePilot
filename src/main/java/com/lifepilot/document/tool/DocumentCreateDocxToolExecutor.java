package com.lifepilot.document.tool;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.model.ToolInput;

/**
 * document.create_docx 工具执行体。
 *
 * <p>流程框架继承自 {@link AbstractDocumentCreateToolExecutor}, 本类只负责:</p>
 * <ul>
 *   <li>声明 MIME / 扩展名 (docx 专属);</li>
 *   <li>解析 {@code markdown} 参数并委托 {@link DocumentGenerator#generate(String)} 生成字节。</li>
 * </ul>
 *
 * <p>落盘 / 入库 / 返回结构由父类统一处理, 保持与 xlsx / pptx 行为对称。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentCreateDocxToolExecutor extends AbstractDocumentCreateToolExecutor {

    private static final String MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String EXT = ".docx";

    private final DocumentGenerator generator;

    public DocumentCreateDocxToolExecutor(DocumentGenerator generator,
                                          SessionDocumentRepository sessionDocumentRepository,
                                          AttachmentRepository attachmentRepository,
                                          String storageDir) {
        super(sessionDocumentRepository, attachmentRepository, storageDir);
        this.generator = generator;
    }

    @Override
    protected String mime() {
        return MIME;
    }

    @Override
    protected String ext() {
        return EXT;
    }

    @Override
    protected byte[] generateBytes(ToolInput input) {
        // 参数缺失 / 类型不匹配时 ToolInput.getParam 会抛 IllegalArgumentException, 由父类捕获
        String markdown = input.getParam("markdown", String.class);
        return generator.generate(markdown);
    }
}
