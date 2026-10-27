package main

/*
#include <unistd.h>
#include "bridge.h"
*/
import "C"

import (
	"os"
	"strings"
)

// Go 运行时在 fatal error / panic 时直接写 fd 2（stderr），
// 而 Android 应用进程的 fd 2 指向 /dev/null —— 崩溃时日志里一个字都留不下。
//
// 这里把 fd 2 重定向到 files/go-stderr.log：
// 用文件而不是管道，是因为进程在 panic 后立刻 abort，
// 管道里没被读走的数据会一起消失；写文件则直接进 page cache，进程死了内容还在。
//
// 文件路径由 /proc/self/cmdline 里的包名推导（app 的 data 目录固定为 /data/data/<pkg>）。
func redirectStderr() {
	pkg := packageName()
	if pkg == "" {
		return
	}

	path := "/data/data/" + pkg + "/files/go-stderr.log"

	// 超过 1 MiB 就重置，避免无限增长
	if info, err := os.Stat(path); err == nil && info.Size() > 1<<20 {
		_ = os.Truncate(path, 0)
	}

	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0600)
	if err != nil {
		return
	}

	fd := file.Fd()
	if C.dup2(C.int(fd), 2) < 0 {
		return
	}

	// 保持 file 引用，避免被 GC 关掉
	stderrFile = file
}

var stderrFile *os.File

func init() {
	redirectStderr()
}

// packageName 取 /proc/self/cmdline 的第一段，并去掉 ":background" 之类的进程后缀，
// 得到 app 的 data 目录名。
func packageName() string {
	data, err := os.ReadFile("/proc/self/cmdline")
	if err != nil {
		return ""
	}

	name := string(data)
	if idx := strings.IndexByte(name, 0); idx >= 0 {
		name = name[:idx]
	}
	if idx := strings.IndexByte(name, ':'); idx >= 0 {
		name = name[:idx]
	}

	return strings.TrimSpace(name)
}
