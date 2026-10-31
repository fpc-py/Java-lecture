package com.polaris.trade.observability;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * 把构建信息暴露到 {@code /actuator/info}（ch13 §3.1 制品溯源）。
 *
 * <p>这件事的价值在事故复盘时体现：
 * <pre>
 *   curl localhost:18081/actuator/info
 *   → {"build":{"commit":"abc1234","time":"2026-10-18T13:40:00+08:00","version":"1.24.0"}}
 *   然后：git show abc1234
 * </pre>
 * 没有它，你只能问"线上现在跑的是哪个版本"，然后得到三种不同的答案。
 *
 * <p>关键前提：{@code git.commit} 必须是 CI 在构建时注入的（{@code --build-arg}），
 * 本地构建时显示 {@code unknown} 才是诚实的 —— 不要用 git 命令在运行时去读，
 * 容器里通常没有 .git 目录，读出来还是 unknown，却让人误以为代码有版本信息。
 */
public class BuildInfoContributor implements InfoContributor {

    private final String commit;
    private final String buildTime;
    private final String version;
    private final String image;

    public BuildInfoContributor(String commit, String buildTime, String version, String image) {
        this.commit = commit;
        this.buildTime = buildTime;
        this.version = version;
        this.image = image;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("build", java.util.Map.of(
                "commit", commit,
                "time", buildTime,
                "version", version,
                "image", image));
        builder.withDetail("runtime", java.util.Map.of(
                "java", System.getProperty("java.version"),
                "jvm", System.getProperty("java.vm.name")));
    }
}
