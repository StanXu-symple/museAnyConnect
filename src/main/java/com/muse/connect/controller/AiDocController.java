package com.muse.connect.controller;

import com.muse.connect.aidoc.AiDocService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Map;

/**
 * 对外暴露基于 {@code @AiApiGroup} / {@code @AiEndpoint} 注解与当前已启用连接器配置动态生成的 AI 可读接口文档。
 * 该控制器位于 {@code /api/**} 路径下，受 {@code RelayAuthFilter} 统一鉴权保护。
 */
@RestController
@RequestMapping("/api/ai")
public class AiDocController {

    private static final MediaType TEXT_MARKDOWN_UTF8 = MediaType.parseMediaType("text/markdown;charset=UTF-8");

    private final AiDocService aiDocService;

    public AiDocController(AiDocService aiDocService) {
        this.aiDocService = aiDocService;
    }

    @GetMapping(value = "/llms.txt", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> llmsTxt(@RequestParam(defaultValue = "false") boolean onlyActive) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String markdown = aiDocService.renderMarkdown(baseUrl, onlyActive);
        return ResponseEntity.ok()
                .contentType(TEXT_MARKDOWN_UTF8)
                .body(markdown);
    }

    @GetMapping(value = "/catalog", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> catalog(@RequestParam(defaultValue = "false") boolean onlyActive) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return aiDocService.buildCatalog(baseUrl, onlyActive);
    }
}
