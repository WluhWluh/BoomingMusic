# S25 用户 Seek 归因分析

测试设备：Samsung Galaxy S25（SM-S9310，Android 15）
应用：`com.wluhwluh.booming.sourcesep.debug`  0.1 版本，开启 playback trace
诊断包：`source-separation-1787147286840.zip`
原始日志：本地忽略目录 `.artifacts/aac-fast-seek/docs/validation/s25-user-seek-final/extracted/playback-gate.log`
逐事件结果：[seek-stage-analysis.csv](seek-stage-analysis.csv)

## 结论

这轮真实用户操作测试不支持“长 seek 延迟由 AAC 压缩回放造成”的判断。AAC 解码器在所有可配对的分离 seek 中都很快完成首帧输出；异常主要出现在混合输出/播放器重新开始送入音频的阶段。更强的对照是《夜に駆ける》关闭音源分离、直接播放原始 FLAC 时仍然出现相同数量级的延迟。

因此，当前应把问题归类为该曲 FLAC/ExoPlayer 音频源 seek、播放器缓冲恢复或音频输出链路问题，而不是 AAC 编码格式本身。AAC 快速 seek 实验线暂不需要因为这轮现象改变编码器或 AAC seek quantum。

## 媒体与样本

| Media ID | 曲目 | 源文件 | 分离 seek | 原始 seek |
| --- | --- | --- | ---: | ---: |
| 481 | John Lennon - Imagine | MP3 | 44 | 0 |
| 398 | Taylor Swift - ... Ready For It? | MP3 | 53 | 0 |
| 1300 | 未知 - luv in b | WAV | 35 | 0 |
| 1544 | YOASOBI - 夜に駆ける | FLAC | 40 | 20 |

原始媒体映射由 S25 MediaStore 查询确认；`1544` 的路径为 `/storage/emulated/0/Music/download/夜に駆ける-YOASOBI-83759543.flac`。

## 阶段统计

下表为 p50 / p95 / 最大值，单位毫秒。分离路径的“恢复估计”是相邻 UI seek marker 间扣除目标位置自然推进后的近似值，因此用于判断量级，不替代播放器内部精确 ready marker。

| 曲目 / 路径 | n | UI -> seek 回调 | UI -> AAC 首帧 | UI -> data ready | UI -> mixed ready | 恢复估计 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Imagine / 分离 | 44 | 9 / 12 / 15 | 13 / 17 / 22 | 34 / 44 / 56 | 47 / 56 / 124 | 227 / 251 / 10581* |
| Ready For It? / 分离 | 53 | 9 / 13 / 23 | 13 / 20 / 29 | 35 / 42 / 47 | 46 / 63 / 122 | 227 / 256 / 423 |
| luv in b / 分离 | 35 | 8 / 11 / 14 | 13 / 19 / 33 | 38 / 153 / 183 | 35 / 94 / 203 | 226 / 307 / 13258* |
| 夜に駆ける / 分离 | 40 | 9 / 10 / 14 | 16 / 24 / 28 | 40 / 60 / 72 | 565 / 1042 / 1052 | 720 / 1239 / 2886 |
| 夜に駆ける / 原始 FLAC | 20 | 约 9 | 不适用 | 不适用 | 不适用 | 689 / 855 / 855 |

`*` 个别恢复估计跨越了下一次 marker 或测试状态切换，不能当作该曲稳定 p99；应以阶段 marker 为准。

《夜に駆ける》分离路径的 40 次 seek 中，AAC 首帧最大只有 28 ms，而 mixed-output ready 的 p50/p95 为 565/1042 ms。原始 FLAC 路径 20 次 seek 的恢复估计为 689/855 ms，和用户听感中的数百毫秒延迟一致。原始路径没有 AAC decoder 或混合器事件，故该对照直接排除了 AAC 解码器作为必要条件。

## 代表事件

分离路径 seek 到 196.926 s：

- UI marker：日志第 7748 行。
- AAC 首帧：第 7760--7761 行，约 14 ms。
- mixed output ready：第 7763 行，约 35 ms。

该样本表现正常，说明远距离 seek 本身不必然导致 AAC 延迟。

另一个分离样本（seek 到 141.526 s）中，AAC 首帧约 14--20 ms，但 mixed output ready 约 633 ms；这正是用户感知到的长空窗所在的阶段。

原始 FLAC 对照 seek 到 183.247 s：UI marker 在第 9063 行，随后立即发生 `player.onPositionDiscontinuity`（第 9064 行），但没有 AAC 或 mixed-output 事件；整次恢复估计约 688 ms。最后一个原始 FLAC 样本（第 9148 行附近）也约 855 ms。

## 归因与边界

1. AAC `extractorSeekUs`、`codecFlushUs` 和首帧输出均为毫秒级，未观察到 AAC decoder 在《夜に駆ける》上系统性变慢。
2. 分离路径的长延迟集中在 `mix.mixedOutputPreroll.ready` 之前或播放器重新开始消费之后；这可能与 FLAC 源 seek、ExoPlayer 的 `BUFFERING` 恢复、音频 sink/输出设备状态或混合器的首批 buffer 调度有关。
3. 原始 FLAC 对照没有独立的“首个可听 PCM” marker，因此无法仅凭当前日志把 689--855 ms 再拆成 extractor、decoder、AudioTrack 各自的耗时。不过它足以证明 AAC 不是必要条件。
4. 这轮是人工连续操作，不是严格随机化实验；恢复估计依赖相邻 marker，且最后一个 seek 缺少下一个 marker。因此报告中的 p95 用于问题定位，不作为产品 SLA。

## 建议的下一步

保持 AAC 实现不变，新增一轮播放器基线 instrumentation：

1. 在原始 FLAC 播放路径记录 `MediaSource` seek、`ExoPlayer.STATE_BUFFERING/READY`、decoder 首个输出 buffer、AudioTrack 首次写入和 `onPositionDiscontinuity` 的纳秒时间戳。
2. 对 `1544` 固定 20 个 seek，分别测试原始 FLAC、单轨 FLAC、AAC 分离和关闭混合器四种路径；每次 seek 后等待稳定播放再开始下一次。
3. 同时记录 AudioTrack underrun、音频路由、输出采样率和设备 thermal 状态，排除蓝牙/USB 路由或系统音频线程唤醒造成的空窗。
4. 只有在原始 FLAC 的 decoder 首帧也明显慢时，才继续优化 FLAC seek；若 decoder 快而 AudioTrack 首写慢，则应排查播放器输出 flush/restart 策略。
