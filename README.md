# Zstd Compresser - Velocity 代理插件

Velocity 代理插件，在 Minecraft 加密之前拦截明文数据包，合并小包，使用 Zstandard (zstd) 压缩后通过代理隧道转发。

---

## 功能

- **小包合并**：在可配置的时间窗口内积攒数据包，最大化压缩效率
- **Zstd 压缩**：用 Zstandard（压缩等级 9、32MB 滑动窗口）替代原版 Zlib，大幅提升压缩率
- **字典训练**：从真实 Minecraft 流量中采样，自动训练优化 zstd 字典
- **自适应跳过**：当 Zstd 帧头开销超过压缩收益时，自动跳过压缩发送原始数据
- **全配置化**：YAML 配置文件，支持压缩等级、窗口大小、攒批上限、训练参数等
- **兼容原版**：未携带 `\0ZSTD\0` 标记的客户端走原版 Zlib，不受影响
- **管理命令**：`/zstd` 命令查看状态、重载配置、触发训练

---

## 运行要求

- Velocity Proxy 3.4.0+
- Java 17+

---

## 安装

1. 下载 `zstd_velocity-<version>-all.jar`
2. 放入 `plugins/` 目录
3. 启动 Velocity —— 首次运行自动生成 `plugins/zstd_velocity/config.yml`
4. 根据需要修改配置，使用 `/zstd reload` 热重载或重启 Velocity

---

## 命令

| 命令             | 功能                                     | 权限             |
|----------------|----------------------------------------|----------------|
| `/zstd status` | 查看压缩参数、在线人数、训练器状态（样本数/字典 ID/字典大小）、配置开关 | `zstd.command` |
| `/zstd reload` | 热重载 config.yml                         | `zstd.command` |
| `/zstd train`  | 触发训练检查，显示当前训练器统计                       | `zstd.command` |

权限可通过 LuckPerms 分配 `zstd.command`。

---

## 配置文件

`plugins/zstd_velocity/config.yml`（首次运行自动生成，含注释）：

```yaml
compression:
  level: 9               # Zstd 压缩等级 (1-22)
  window_log: 25          # 滑动窗口 2^N 字节 (25 = 32MB)

trainer:
  max_samples: 10000      # 采样环缓冲区上限
  min_samples: 2000       # 最少样本数触发训练
  cooldown_ms: 300000     # 训练冷却时间 (ms, 5分钟)
  fallback_timeout_ms: 600000  # 兜底超时强制训练 (ms, 10分钟)
  dict_max_bytes: 131072  # 字典大小上限 (默认 128KB)
  sample_target_bytes: 1048576  # 样数据目标大小
  max_history_samples: 50000   # 跨重启持久化历史样本上限
  adoption_threshold: 0.03     # 压缩率提升 ≥ 3% 才采纳新字典
  prune_min_payload: 16        # 丢弃有效载荷小于此值的包 (字节)

logging:
  stats_enabled: false    # TX/RX 统计日志
  stats_interval_sec: 10  # 统计输出间隔 (秒)
  debug: false            # 调试日志（管道详情、SetCompression 流程）
```

---

## 兼容性

- **原版客户端**：安全——未附加 `\0ZSTD\0` 标记的客户端走原版 Zlib 压缩，不受影响
- **ViaVersion**：兼容——所有关键操作在 ViaVersion 处理器前后正确注入
- **ServerSwitcher**：兼容——双方各自向握手包主机名追加不同标记，互不干扰
- **其他插件**：LimboAuth、VelocityBandwidthPacer 等均兼容

---

## 工作原理

1. 客户端在握手包主机名末尾附加 `\0ZSTD\0` 标记
2. Velocity 检测到标记，注入 Netty 管道拦截处理器
3. 登录阶段协商 zstd 支持，附带已训练的字典二进制数据（如有）
4. 双方替换压缩处理器为 Zstd
5. 压缩开销大于收益时自动发送未压缩帧

字典训练从数据包采样中异步进行，训练结果持久化到磁盘，重启后自动加载。

---

## 客户端要求

需要客户端同时安装 `zstd_compresser` 模组。

---

## License

本项目采用 [AGPL-3.0 License](LICENSE) 开源许可。
