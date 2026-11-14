package io.lytgos.core;

/** 临时探针：故意不合格式，用于验证 CI 对未格式化代码的阻断，验证后由 revert 提交移除。 */
public final class FormatProbeCi {
      private FormatProbeCi() {}


    public static int probe() {
        return 1;
    }
}
