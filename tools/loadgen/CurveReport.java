import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 loadtest.sh 产出的 curve.csv 渲染成可贴进复盘文档的 markdown 报告。
 *
 * <p>单独成一个文件而不是塞进 loadtest.sh 的 heredoc：
 * JDK 的单文件源码启动器只接受<b>文件路径</b>，不接受 stdin（{@code java -}）。
 * 顺带的好处是这个类可以单独拿来跑，把任何一份历史 csv 重新渲染。
 *
 * <p>用法：{@code java tools/loadgen/CurveReport.java curve.csv curve.md query "描述" 15}
 */
public final class CurveReport {

    private static final char[] BLOCKS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法：CurveReport <curve.csv> <curve.md> [mode] [desc] [durationSec]");
            System.exit(1);
        }
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        Path out = Path.of(args[1]);
        String mode = args.length > 2 ? args[2] : "query";
        String desc = args.length > 3 ? args[3] : "";
        String dur = args.length > 4 ? args[4] : "15";

        List<String[]> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (!l.isEmpty()) {
                rows.add(l.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)"));
            }
        }
        if (rows.isEmpty()) {
            System.err.println("curve.csv 里没有数据行");
            System.exit(2);
        }

        double maxQps = rows.stream().mapToDouble(r -> parse(r[2])).max().orElse(1);
        StringBuilder sb = new StringBuilder();
        sb.append("# 压测曲线 · ").append(mode).append("\n\n");
        sb.append("- 场景：").append(desc).append('\n');
        sb.append("- 每档时长：").append(dur).append("s（不含预热）\n");
        sb.append("- 压测器：`tools/loadgen/LoadGen.java`（JDK 21 虚拟线程，无外部依赖）\n\n");

        sb.append("| 并发 | QPS | P50(ms) | P90(ms) | P99(ms) | Max(ms) | 错误率 | 状态码 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (String[] r : rows) {
            sb.append("| ").append(r[0]).append(" | ").append(r[2])
                    .append(" | ").append(r[3]).append(" | ").append(r[4])
                    .append(" | ").append(r[5]).append(" | ").append(r[6])
                    .append(" | ").append(fmtPct(r[8])).append(" | `").append(r[9]).append("` |\n");
        }

        sb.append("\n## QPS 曲线\n\n```\n");
        for (String[] r : rows) {
            double qps = parse(r[2]);
            int level = (int) Math.round(qps / maxQps * (BLOCKS.length - 1));
            level = Math.max(0, Math.min(level, BLOCKS.length - 1));
            sb.append(String.format("%5s │%s %8.1f%n", r[0],
                    String.valueOf(BLOCKS[level]).repeat(level + 1), qps));
        }
        sb.append("      └").append("─".repeat(30)).append(" 并发\n```\n");

        sb.append("\n## 怎么读这张图\n\n");
        sb.append("1. **QPS 还在涨**：系统没到瓶颈，加并发就能加吞吐。\n");
        sb.append("2. **QPS 变平而 P99 起飞**：到拐点了。多出来的并发全在排队，");
        sb.append("再压只会让所有人更慢 —— 这就是单实例的容量天花板。\n");
        sb.append("3. **错误率出现**：线程池开始拒绝 / 连接池超时 / 限流生效。");
        sb.append("从这里往后 QPS 的数字已经不可信了（失败请求的成本远低于成功请求）。\n");
        sb.append("\n> 安全水位 = 拐点 QPS × 0.7。这个 0.7 是拐点的函数，不是拍出来的。\n");

        for (int i = 1; i < rows.size(); i++) {
            double prevQps = parse(rows.get(i - 1)[2]);
            double qps = parse(rows.get(i)[2]);
            long prevP99 = (long) parse(rows.get(i - 1)[5]);
            long p99 = (long) parse(rows.get(i)[5]);
            if (prevQps > 0 && (qps - prevQps) / prevQps < 0.10
                    && prevP99 > 0 && (double) p99 / prevP99 > 1.5) {
                sb.append("\n**识别到拐点**：并发 ").append(rows.get(i - 1)[0])
                        .append(" → ").append(rows.get(i)[0])
                        .append(" 时 QPS 只涨 ").append(String.format("%.1f%%", (qps - prevQps) / prevQps * 100))
                        .append("，而 P99 涨 ").append(String.format("%.0f%%", (double) p99 / prevP99 * 100 - 100))
                        .append("。单实例上限 ≈ ").append(String.format("%.0f", prevQps))
                        .append(" QPS，安全水位 ≈ ").append(String.format("%.0f", prevQps * 0.7))
                        .append(" QPS。\n");
                break;
            }
        }

        Files.writeString(out, sb.toString());
        System.out.println(out);
    }

    private static double parse(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String fmtPct(String v) {
        try {
            return String.format("%.2f%%", Double.parseDouble(v.trim()) * 100);
        } catch (NumberFormatException e) {
            return v;
        }
    }
}
