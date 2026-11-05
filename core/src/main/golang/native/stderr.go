package main

/*
#include <unistd.h>
#include "bridge.h"
*/
import "C"

import (
	"os"
	"strings"
	"unsafe"
)

// replayLogTag 是回放日志在 logcat 里的 tag，方便过滤：
//   adb logcat -s CMFA-GoStderr
const replayLogTag = "CMFA-GoStderr"

// replayLogChunk 单条 logcat 消息的最大长度（logcat 单条上限约 4KB）。
const replayLogChunk = 2000

// replayPreviousStderr 把上一次进程留下的 go-stderr.log 回放到 logcat，然后清空。
//
// 主用路径已经是外部私有目录（adb 可直接 pull），这里保留 logcat 回放作为兜底：
// 崩溃后进程会被重新拉起，新进程在 init 阶段即可把上一轮的遗言打出去。
func replayPreviousStderr(path string) {
	info, err := os.Stat(path)
	if err != nil || info.Size() == 0 {
		return
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return
	}

	// 只回放最后 256 KiB，避免超大文件把 logcat 淹了；panic 文本总在末尾。
	const maxReplay = 256 << 10
	if len(data) > maxReplay {
		data = data[len(data)-maxReplay:]
	}

	tag := C.CString(replayLogTag)
	defer C.free(unsafe.Pointer(tag))

	for offset := 0; offset < len(data); offset += replayLogChunk {
		end := offset + replayLogChunk
		if end > len(data) {
			end = len(data)
		}

		chunk := C.CString(string(data[offset:end]))
		C.__android_log_write(C.ANDROID_LOG_ERROR, tag, chunk)
		C.free(unsafe.Pointer(chunk))
	}

	_ = os.Truncate(path, 0)
}

// Go 运行时在 fatal error / panic 时直接写 fd 2（stderr），
// 而 Android 应用进程的 fd 2 指向 /dev/null —— 崩溃时日志里一个字都留不下。
//
// 这里把 fd 2 重定向到 go-stderr.log：
// 用文件而不是管道，是因为进程在 panic 后立刻 abort，
// 管道里没被读走的数据会一起消失；写文件则直接进 page cache，进程死了内容还在。
//
// 路径优先取外部私有目录（adb 可 pull），见 stderrPath。
func redirectStderr(path string) {
	// 超过 1 MiB 就重置，避免无限增长
	if info, err := os.Stat(path); err == nil && info.Size() > 1<<20 {
		_ = os.Truncate(path, 0)
	}

	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		return
	}

	// 显式放宽到 0644：落在外部目录时 adb shell 属于 other，0600 会被拒读。
	// 外部私有目录本身只有本应用与 shell 可进入，不存在泄露面。
	_ = file.Chmod(0o644)

	fd := file.Fd()
	if C.dup2(C.int(fd), 2) < 0 {
		return
	}

	// 保持 file 引用，避免被 GC 关掉
	stderrFile = file
}

var stderrFile *os.File

// externalStderrRoot 用于判断日志是否落在可被 adb 直接读取的位置。
const externalStderrRoot = "/storage/emulated/0/Android/"

func init() {
	path := stderrPath()
	if path == "" {
		return
	}

	// 外部目录的文件可以直接 adb pull，既不必回放也不能清空（清空就把证据销毁了）。
	if !strings.HasPrefix(path, externalStderrRoot) {
		replayPreviousStderr(path)
	}

	redirectStderr(path)
}

// stderrPath 返回崩溃日志的落地路径。
//
// 优先用外部私有目录 /storage/emulated/0/Android/data/<pkg>/files：
// app 写这里不需要任何权限，而 adb shell 属于 ext_data_rw 组、可以直接 pull 出来，
// 既不受「release 包不可 run-as」的限制，也不依赖 logcat（部分 ROM 会屏蔽应用日志）。
// 外部存储尚未挂载时回退到内部 data 目录。
func stderrPath() string {
	pkg := packageName()
	if pkg == "" {
		return ""
	}

	if dir := "/storage/emulated/0/Android/data/" + pkg + "/files"; dirWritable(dir) {
		return dir + "/go-stderr.log"
	}

	return "/data/data/" + pkg + "/files/go-stderr.log"
}

// dirWritable 检查目录是否可写（外部存储可能还没挂载）。
func dirWritable(dir string) bool {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return false
	}

	f, err := os.CreateTemp(dir, ".probe-*")
	if err != nil {
		return false
	}

	name := f.Name()
	_ = f.Close()
	_ = os.Remove(name)

	return true
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
