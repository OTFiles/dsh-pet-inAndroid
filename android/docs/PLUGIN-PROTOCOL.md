# dsh-pet Android 插件协议（Agent 联动事件总线）

> 对齐上游桌面端 `docs/AGENT_LINK_PROTOCOL.md` 统一事件协议。零网络、零代码接入。

## 快速接入（任意 App / 脚本 / AI Agent）

往这个文件**追加写**一行 JSON 即可：

```
/sdcard/dsh-pet/agent-events/<你的agent名>.jsonl
```

例如（Tasker / adb shell / Root 脚本 / 任何有存储权限的 App）：

```bash
adb shell "echo '{\"ts\": $(date +%s), \"agent\": \"claude\", \"state\": \"working\"}' >> /sdcard/dsh-pet/agent-events/claude.jsonl"
```

在桌宠 **设置 → AI 对话 → Agent 联动（插件）** 打开「监听 Agent 事件」后，桌宠会实时响应（1.5s 内）。

## 事件格式（每行一个 JSON）

```jsonc
// 形态一：显式状态（优先级最高）
{"ts": 1750000000.0, "agent": "myagent", "state": "working"}

// 形态二：事件名（按内置映射归一，与 Claude Code hooks 兼容）
{"ts": 1750000000.0, "agent": "myagent", "event": "PreToolUse", "tool": "bash"}
```

## 六态词汇与桌宠反应

| 状态 | 含义 | 桌宠反应 |
|---|---|---|
| `thinking` | 思考中 | 联动动画（写代码/敲击桌面…） |
| `working` | 执行中 | 同上 |
| `attention` | 需要用户注意 | 重要气泡「需要你看一眼～」 |
| `error` | 出错 | 气泡「好像遇到报错了…」 |
| `idle` | 空闲 | 恢复待机；busy→idle 触发「干完活啦」 |
| `sleeping` | 休眠 | 同 idle |

## 内置事件名 → 状态映射

| 事件名 | 状态 |
|---|---|
| `SessionStart` / `SessionEnd` | `idle` |
| `UserPromptSubmit` | `thinking` |
| `PreToolUse` / `PostToolUse` | `working` |
| `PostToolUseFailure` / `StopFailure` | `error` |
| `Stop` / `SubagentStop` | `attention` |

不在表内的事件名一律忽略（防误触发）。

## 写方注意

1. **追加写**（append-only），UTF-8；
2. 连续相同状态**去重**（不要重复落盘）；
3. 文件超 1MB 时自行轮转（`x.jsonl` → `x.jsonl.1`，读方自动适配）；
4. 写失败**静默放弃**——联动是锦上添花，不能阻塞你的 Agent；
5. **隐私红线**：只写状态元数据（状态/事件名/工具名），绝不要写代码内容、命令全文、文件内容。

## 读方保证

- byte-offset 增量 tail：不回放历史事件（新文件从末尾开始 tail）；
- 半行缓冲不丢事件；单次读取有界 64KB；
- 文件不存在/目录不存在时静默空转；
- 坏行直接忽略，绝不崩溃。

## Claude Code 一键接入示例（手机 Termux 里的 Claude Code）

把以下 hooks 写进 `~/.claude/settings.json`（Claude Code 会自动在事件时机执行命令）：

```json
{
  "hooks": {
    "PreToolUse":  [{"command": "echo '{\"agent\":\"claude\",\"event\":\"PreToolUse\"}' >> /sdcard/dsh-pet/agent-events/claude.jsonl"}],
    "PostToolUse": [{"command": "echo '{\"agent\":\"claude\",\"event\":\"PostToolUse\"}' >> /sdcard/dsh-pet/agent-events/claude.jsonl"}],
    "Stop":        [{"command": "echo '{\"agent\":\"claude\",\"event\":\"Stop\"}' >> /sdcard/dsh-pet/agent-events/claude.jsonl"}]
  }
}
```

（Termux 无 `/sdcard` 写权限时先 `termux-setup-storage`；或把事件目录用 adb 同步。）
