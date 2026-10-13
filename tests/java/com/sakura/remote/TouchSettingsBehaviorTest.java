package com.sakura.remote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 「显示 / 穿透偏好」的真实生产逻辑行为测试（TS-03 / TS-04 / TS-05 / TS-06）。
 *
 * <p>被测的是真实生产类 {@link PetConfig} 与 {@link DisplayPreferences}，
 * Android 的 Context / SharedPreferences 用 tests/androidstub-overlay 的内存替身
 * （真实存值 + 可注入 commit 失败），因此这里断言的是**提交确认后回读**的值，
 * 不是「内存里改了就假定成功」。
 *
 * <p>覆盖：
 *   - 无偏好时穿透默认开、浮动默认关；
 *   - 明确选择后 has/value 都正确、重复读保持（重启保持同一份状态）；
 *   - applyDisplayPreferences 的 ok/invalid/回读一致/幂等；
 *   - 只写一个 key 不误改另一个；
 *   - 失败响应只含稳定安全字符。
 */
public final class TouchSettingsBehaviorTest {

    private static int failures;

    private static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name
                + (ok ? "" : " -> " + String.valueOf(detail)));
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        Context ctx = new Context();

        // ---- 默认值 ----
        check("无偏好：穿透默认开启且 has=false",
                PetConfig.passThroughEnabled(ctx) && !PetConfig.hasPassThroughPreference(ctx),
                PetConfig.passThroughEnabled(ctx) + "/" + PetConfig.hasPassThroughPreference(ctx));
        check("无偏好：浮动默认关闭且 has=false",
                !PetConfig.portraitFloatEnabled(ctx) && !PetConfig.hasPortraitFloatPreference(ctx),
                PetConfig.portraitFloatEnabled(ctx) + "/" + PetConfig.hasPortraitFloatPreference(ctx));

        // ---- 明确选择 ----
        PetConfig.setPassThroughEnabled(ctx, false);
        check("选择穿透关：has=true / value=false",
                PetConfig.hasPassThroughPreference(ctx) && !PetConfig.passThroughEnabled(ctx),
                PetConfig.passThroughEnabled(ctx));
        PetConfig.setPortraitFloatEnabled(ctx, true);
        check("选择浮动开：has=true / value=true",
                PetConfig.hasPortraitFloatPreference(ctx) && PetConfig.portraitFloatEnabled(ctx),
                PetConfig.portraitFloatEnabled(ctx));

        // ---- 快照协议 ----
        String snapshot = PetConfig.displayPreferences(ctx, true);
        check("快照带 supported=true", snapshot.contains("\"supported\":true"), snapshot);
        check("快照带 has/value 三态",
                snapshot.contains("\"passThrough\":{\"has\":true,\"value\":false,\"supported\":true}"),
                snapshot);
        check("快照不支持的机型如实报 supported=false",
                PetConfig.displayPreferences(ctx, false).contains("\"supported\":false"),
                PetConfig.displayPreferences(ctx, false));

        // ---- apply：回读一致 + 幂等 ----
        String first = PetConfig.applyDisplayPreferences(ctx, "{\"passThrough\":true}");
        check("apply 返回 ok:true", first.contains("\"ok\":true"), first);
        check("apply 回执就是落盘值",
                first.contains("\"passThrough\":{\"has\":true,\"value\":true}"), first);
        String second = PetConfig.applyDisplayPreferences(ctx, "{\"passThrough\":true}");
        check("重复 apply 幂等", first.equals(second), first + " vs " + second);

        // ---- 单 key 隔离 ----
        check("只写穿透不误改浮动", PetConfig.portraitFloatEnabled(ctx),
                PetConfig.portraitFloatEnabled(ctx));
        String floatOnly = PetConfig.applyDisplayPreferences(ctx, "{\"float\":false}");
        check("只写浮动不误改穿透",
                floatOnly.contains("\"passThrough\":{\"has\":true,\"value\":true}"), floatOnly);
        check("只写浮动确实落盘", !PetConfig.portraitFloatEnabled(ctx), "still true");

        // ---- 非法输入 ----
        check("无识别 key 返回 invalid",
                "{\"ok\":false,\"error\":\"invalid\"}"
                        .equals(PetConfig.applyDisplayPreferences(ctx, "{\"nope\":1}")),
                PetConfig.applyDisplayPreferences(ctx, "{\"nope\":1}"));
        check("空串返回 invalid",
                "{\"ok\":false,\"error\":\"invalid\"}"
                        .equals(PetConfig.applyDisplayPreferences(ctx, "")),
                PetConfig.applyDisplayPreferences(ctx, ""));
        check("畸形 JSON 不崩溃且 invalid",
                "{\"ok\":false,\"error\":\"invalid\"}"
                        .equals(PetConfig.applyDisplayPreferences(ctx, "{\"passThrough\":")),
                PetConfig.applyDisplayPreferences(ctx, "{\"passThrough\":"));

        // ---- 失败响应去敏 ----
        String failure = DisplayPreferences.failure("bad code!<script>");
        check("失败响应只含稳定安全字符",
                failure.matches("\\{\"ok\":false,\"error\":\"[a-z0-9-]*\"\\}"), failure);
        check("空错误码回退到 invalid",
                DisplayPreferences.failure("").contains("\"error\":\"invalid\""),
                DisplayPreferences.failure(""));

        // ---- 原语 ----
        check("bool 只认裸 true/false",
                DisplayPreferences.bool("{\"passThrough\":true}", "passThrough") == Boolean.TRUE
                        && DisplayPreferences.bool("{\"passThrough\":\"true\"}", "passThrough") == null
                        && DisplayPreferences.bool("{\"x\":1}", "passThrough") == null,
                "bool parsing");
        check("hasAny 识别两个 key",
                DisplayPreferences.hasAny("{\"float\":true}")
                        && DisplayPreferences.hasAny("{\"passThrough\":false}")
                        && !DisplayPreferences.hasAny("{\"other\":1}"),
                "hasAny");

        // ---- 同时写两个 key（App 保存按钮路径）----
        String both = PetConfig.applyDisplayPreferences(ctx,
                "{\"float\":true,\"passThrough\":false}");
        check("同时写两个 key 的回执一致",
                both.contains("\"float\":{\"has\":true,\"value\":true}")
                        && both.contains("\"passThrough\":{\"has\":true,\"value\":false}"), both);
        check("同时写两个 key 落盘正确",
                PetConfig.portraitFloatEnabled(ctx) && !PetConfig.passThroughEnabled(ctx),
                PetConfig.portraitFloatEnabled(ctx) + "/" + PetConfig.passThroughEnabled(ctx));

        // ---- commit 失败：稳定错误码 + 不覆盖旧偏好（磁盘写入确认）----
        PetConfig.applyDisplayPreferences(ctx, "{\"float\":true,\"passThrough\":true}");
        SharedPreferences.Memory memory = ctx.memoryPrefs(PetConfig.PREFS);
        boolean beforeFloat = PetConfig.portraitFloatEnabled(ctx);
        boolean beforePass = PetConfig.passThroughEnabled(ctx);
        int snapshotsBefore = memory.appliedSnapshots.size();
        memory.failCommits = true;
        String failed = PetConfig.applyDisplayPreferences(ctx,
                "{\"float\":false,\"passThrough\":false}");
        memory.failCommits = false;
        check("commit 失败返回稳定错误码 commit-failed",
                "{\"ok\":false,\"error\":\"commit-failed\"}".equals(failed), failed);
        check("commit 失败不覆盖用户旧偏好",
                PetConfig.portraitFloatEnabled(ctx) == beforeFloat
                        && PetConfig.passThroughEnabled(ctx) == beforePass,
                PetConfig.portraitFloatEnabled(ctx) + "/" + PetConfig.passThroughEnabled(ctx));
        check("commit 失败不新增成功落盘快照",
                memory.appliedSnapshots.size() == snapshotsBefore,
                memory.appliedSnapshots.size() + " vs " + snapshotsBefore);
        check("失败提交被记录（可诊断）", memory.failedCommits >= 1, memory.failedCommits);
        String recovered = PetConfig.applyDisplayPreferences(ctx,
                "{\"float\":false,\"passThrough\":false}");
        check("commit 恢复后写入成功且值正确",
                recovered.contains("\"ok\":true")
                        && !PetConfig.portraitFloatEnabled(ctx)
                        && !PetConfig.passThroughEnabled(ctx), recovered);

        // ---- alpha 安全阈值幂等（Android 12+ 遮挡规则）----
        PassThroughAlpha alpha = new PassThroughAlpha();
        float alphaFirst = alpha.enable(1f, 0.8f);
        check("首次开启按安全上限降低 alpha",
                Math.abs(alphaFirst - 0.8f * 0.98f) < 1e-5f && alpha.isReduced(), alphaFirst);
        check("首次开启保存的原始 alpha 是 1", Math.abs(alpha.originalAlpha() - 1f) < 1e-6f,
                alpha.originalAlpha());
        float again = alpha.enable(alphaFirst, 0.8f);
        check("重复开启保持同一目标（不把已降低值当原始值）",
                Math.abs(again - alphaFirst) < 1e-6f && Math.abs(alpha.originalAlpha() - 1f) < 1e-6f,
                again + "/" + alpha.originalAlpha());
        float restored = alpha.disable();
        check("关闭后恢复最初不透明度", Math.abs(restored - 1f) < 1e-6f && !alpha.isAdjusted(),
                restored);
        check("恢复后再次开启仍是同一目标（真正的幂等）",
                Math.abs(alpha.enable(restored, 0.8f) - alphaFirst) < 1e-5f,
                alpha.enable(restored, 0.8f));

        PassThroughAlpha noLimit = new PassThroughAlpha();
        check("系统不限制时不额外变淡", Math.abs(noLimit.enable(1f, 1f) - 1f) < 1e-6f
                && !noLimit.isReduced(), noLimit.enable(1f, 1f));
        PassThroughAlpha alreadyLow = new PassThroughAlpha();
        check("本来就更低时不额外变淡",
                Math.abs(alreadyLow.enable(0.5f, 0.9f) - 0.5f) < 1e-6f && !alreadyLow.isReduced(),
                alreadyLow.enable(0.5f, 0.9f));
        PassThroughAlpha invalid = new PassThroughAlpha();
        check("非法 alpha 归一到 1", Math.abs(invalid.enable(0f, 0.8f) - 0.8f * 0.98f) < 1e-5f
                && Math.abs(invalid.originalAlpha() - 1f) < 1e-6f, invalid.originalAlpha());

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production sources: PetConfig/DisplayPreferences)");
    }
}
