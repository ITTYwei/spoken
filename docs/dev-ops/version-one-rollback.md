# 版本一：并发改造前基线（2026-10-15）

本地 Git 标签：`version-one-20261015`。
提交：`ea19fdb5af41d5f61ce0997df0b07e227795e69a`。

本地完整源码快照（包含当时未跟踪的文档和脚本）：
`rollback-backups/version-one-20261015/spoken-server-v1.tar.gz`

SHA256：`b7d9b3249f4198b7c929abfd9480a053d3d893e162642f0f80732e7bd1f687ab`。

服务器备份：`/home/nm/develop/code/backups/version-one-20261015-180327/`。
包含 `opentalking.tar.gz`、`bridge.tar.gz`、`flashhead.tar.gz`，分别对应 OpenTalking、spokenai-bridge 和 backup/SoulX-FlashHead；`manifest.json` 记录源路径和校验值。
服务器归档包括源码和配置，可能包含私有配置，仅保存在服务器受限目录，不能提交或公开。
模型目录、虚拟环境、node_modules、Git 元数据和生成结果没有打包；恢复依赖原有权重及运行环境。这是代码基线，不是数据库、运行中会话或整机快照。

所有归档已重新打开检查目录并计算 SHA256；未进行停服恢复演练。

## 回滚步骤

1. 先保存并发改造后的代码，避免丢失排查材料。
2. 本地用 `git switch --detach version-one-20261015` 查看版本一；有未提交变更时先保存。需要恢复未跟踪文件时，将本地归档解压到新目录核对后恢复。
3. 服务器先将归档解压到独立恢复目录，与 manifest 和原路径核对。不要直接覆盖运行中的代码，也不要删除原有模型或虚拟环境。
4. 停止需要恢复的 OpenTalking 与 FlashHead 服务；从三个归档恢复对应源码及配置，保留现有权重和依赖目录，并保留符号链接。
5. 使用原有服务管理方式启动 FlashHead 和 OpenTalking。先检查 API，再验证单用户建连、音视频、打断、关闭和槽位释放。

本次仅建立版本一标记和备份，没有部署并发改造，也没有重启服务。
