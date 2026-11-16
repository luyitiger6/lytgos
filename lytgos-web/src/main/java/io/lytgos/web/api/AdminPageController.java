package io.lytgos.web.api;

import io.lytgos.core.LytgosException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读管理台：把 classpath 里的单页 HTML 原样返回。
 *
 * <p>不用 {@code static/} 目录的默认索引：显式控制器让「{@code /admin/} 返回什么、什么类型」可断言，也不依赖 WebMvc 的欢迎页配置。
 *
 * <p>页面在装配期一次性读入：几百 KB 以内的小文件，没必要每个请求都打一次 classpath； 文件缺失属于打包错误， 应当启动即失败，而不是等到用户打开页面才发现。
 */
@RestController
public class AdminPageController {

  static final String PAGE_PATH = "admin/index.html";

  private static final MediaType HTML_UTF8 =
      new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

  private final String html;

  private AdminPageController(String html) {
    this.html = html;
  }

  /**
   * 读入 classpath 页面并构造控制器，由自动配置在装配期调用。
   *
   * <p>读取失败直接抛出：文件缺失属于打包错误，应当启动即失败。构造器只接收已读好的内容， 让「启动失败」发生在装配层而不是留一个可能半初始化的对象。
   */
  public static AdminPageController load() {
    ClassPathResource page = new ClassPathResource(PAGE_PATH);
    if (!page.exists()) {
      throw new LytgosException("管理台页面资源缺失: " + PAGE_PATH);
    }
    try {
      return new AdminPageController(page.getContentAsString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("管理台页面读取失败: " + PAGE_PATH, e);
    }
  }

  /** 带不带尾斜杠都返回同一页：两个地址都有人手敲，404 只会被当成服务坏了。 */
  @GetMapping({"/admin", "/admin/"})
  public ResponseEntity<String> page() {
    return ResponseEntity.ok().contentType(HTML_UTF8).body(html);
  }
}
