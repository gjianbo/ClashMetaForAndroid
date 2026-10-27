# mihomo 上游补丁

`core/src/foss/golang/clash` 是指向 `MetaCubeX/mihomo`（branch `Alpha`）的 git submodule。
本目录存放我们对上游源码的改动，**构建时自动应用**，使 submodule 始终保持指向上游。

## 为什么不直接改 submodule

父仓库（本仓库）里，`core/src/foss/golang/clash` 不是普通目录，而是一根指针，**只记录它指向哪个 commit**，不记录里面的文件内容。

所以：

| 状态显示 | 含义 | 能否 `git add` |
|---|---|---|
| ` M`（大写） | 子模块 HEAD 换了 commit | 能，add 会更新指针 |
| ` m`（小写） | 子模块**工作区**有改动 | **不能**，父仓库看不到内容 |

在 submodule 里直接 `commit` 也解决不了：那个 commit 推不到上游（没权限），而且任何人跑 `git submodule update` 都会被重置掉。

## 补丁清单

| 文件 | 作用 |
|---|---|
| `0001-tailscale-status-api.patch` | 给 `adapter/outbound/tailscale.go` 加 `Status()` / `Ping()` 方法与实例注册表 `LookupTailscale()`，供 Tailscale 状态页读取 tsnet 的节点、延迟、连接方式 |

## 自动应用

`core/build.gradle.kts` 中的 `patchClashSources` 任务会在任意 `externalGolangBuild*` 之前执行：

- 尚未应用 → 应用
- 已经应用 → 跳过（幂等，不会重复打）
- 无法应用（上游改动了同一处）→ 构建直接报错，不会静默产出错误包

## 手动操作

```bash
# 还原全部补丁，让 submodule 回到上游干净状态
gradle :core:revertClashPatches

# 更新上游后重新构建，补丁会自动重新打上
git submodule update --remote core/src/foss/golang/clash
gradle :app:assembleAlphaDebug
```

> 若本机 Gradle wrapper 不可用（`services.gradle.org` 被墙），用本地安装的 gradle 代替 `./gradlew`。

## 上游冲突怎么办

若上游改动了 `tailscale.go` 导致补丁打不上，构建会报错并指出是哪个补丁。处理：

```bash
# 1. 先还原
gradle :core:revertClashPatches

# 2. 手工把改动重新加回 src/foss/golang/clash/adapter/outbound/tailscale.go

# 3. 导出成新补丁（覆盖旧文件）
git -C core/src/foss/golang/clash diff > patches/0001-tailscale-status-api.patch

# 4. 重新构建验证
gradle :app:assembleAlphaDebug
```

## 注意

补丁应用后，`git status` 会显示：

```
m core/src/foss/golang/clash        # 父仓库：工作区脏
```

进入子模块看是 ` M adapter/outbound/tailscale.go`。

**这是预期状态，不需要也不应该 `git add` 它。** 只要不提交 submodule 指针，父仓库的 commit 就不受影响。想让工作区干净时跑 `revertClashPatches` 即可。
