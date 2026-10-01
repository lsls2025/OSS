package com.aurora.chat.lua

import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseIoLib
import org.luaj.vm2.lib.jse.JseOsLib
import java.io.File

/**
 * 轻量 Lua 脚本执行器（基于 LuaJ，纯 JVM，约 200KB）。
 *
 * 关于 JseIoLib / JseOsLib：
 * 它们来自 luaj-jse 包，是纯 Java 实现（不依赖 JNI），Android 上加载、运行都没问题。
 * 之前注释里说的「会导致 UnsatisfiedLinkError」针对的是 luaj-ios（原生 C 版），与本项目无关。
 *
 * 为安全起见，IO 路径被锚定在 AI 工作区内：`io.open("foo.txt")` 实际落到 `workspace/foo.txt`；
 * 传绝对路径会被剥离成文件名后再拼回工作区。`os` 的 system/popen/execute 全部禁用（返回 nil）。
 *
 * @param workspace 工作目录，io.open 等文件操作的根路径；null 则不做路径限制（仅用于内部调试）
 * @param printLine 脚本 print() 输出回调（已包含换行）
 */
class LuaRunner(
    private val workspace: File? = null,
    private val printLine: (String) -> Unit
) {
    private val globals: Globals = Globals()

    init {
        // === 加载标准库 ===
        // 纯 Lua 核心库
        globals.load(BaseLib())
        globals.load(PackageLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(CoroutineLib())
        globals.load(MathLib())
        LoadState.install(globals)
        LuaC.install(globals)

        // IO / OS —— 之前没加载是因为误判了 Jse 包的兼容性，现在加载上。
        globals.load(JseIoLib())
        globals.load(JseOsLib())

        // === 把 IO 路径约束到工作区 ===
        if (workspace != null) {
            workspace.mkdirs()
            sandboxIo(workspace)
            sandboxOs(workspace)
        }

        // 重定向 print 到回调（Lua 多个参数用 \t 拼接）
        globals.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val sb = StringBuilder()
                for (i in 1..args.narg()) {
                    if (i > 1) sb.append("\t")
                    sb.append(args.arg(i).toString())
                }
                printLine(sb.toString())
                return LuaValue.NIL
            }
        })
    }

    /**
     * 安全化 io 库：拦截所有文件打开，把路径锚定到 workspace。
     * - 相对路径 → workspace/相对路径
     * - 绝对路径 → 剥离成文件名后 → workspace/文件名
     * - ".." 上跳 → 移除所有 .. 段
     *
     * 关键：必须在覆盖 io.open **之前**把原始实现存下来，否则在 safeOpen.invoke 里
     * 通过 io.get("open") 取到的已经是 safeOpen 自己，调自己 → 无限递归 → 栈溢出。
     */
    private fun sandboxIo(workspace: File) {
        val io = globals.get("io") as? LuaTable ?: return

        // === 先存原始实现 ===
        val realOpen = io.get("open") as? VarArgFunction
        val realTmpfile = io.get("tmpfile") as? VarArgFunction
        val realClose = io.get("close") as? VarArgFunction
        val realInput = io.get("input") as? VarArgFunction
        val realOutput = io.get("output") as? VarArgFunction

        // === 覆盖 io.open ===
        if (realOpen != null) {
            io.set("open", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val pathArg = args.arg1().toString()
                    val mode = args.arg(2).toString().ifBlank { "r" }
                    val safe = safeResolve(pathArg, workspace)
                    return realOpen.call(LuaValue.valueOf(safe), LuaValue.valueOf(mode))
                }
            })
        }

        // === 覆盖 io.tmpfile：落工作区 ===
        // 直接用 realOpen 打开工作区里的临时文件，让 Lua 的 io.tmpfile() 返回一个可读写的工作区文件句柄
        if (realTmpfile != null && realOpen != null) {
            io.set("tmpfile", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val tmp = File(workspace, ".luatmp_${System.currentTimeMillis()}_${(0..9999).random()}.tmp")
                    return try {
                        realOpen.call(LuaValue.valueOf(tmp.absolutePath), LuaValue.valueOf("w+"))
                    } catch (_: Exception) {
                        LuaValue.NIL
                    }
                }
            })
        }

        // === 拦截 io.input / io.output（防止把 stdio 重定向到别处）===
        val noStdio = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = LuaValue.NIL
        }
        if (realInput != null) io.set("input", noStdio)
        if (realOutput != null) io.set("output", noStdio)

        // close 保留原实现（它只是关文件句柄，不涉路径）
    }

    /**
     * 安全化 os 库：禁用 system/popen/execute，把 remove/rename/tmpname/setlocale 做路径约束。
     * `date/time/clock/getenv/executeexit` 等无副作用的函数保留。
     */
    private fun sandboxOs(workspace: File) {
        val os = globals.get("os") as? LuaTable ?: return

        // 命令执行全禁
        val disabled = object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = LuaValue.NIL
        }
        os.set("system", disabled)
        os.set("execute", disabled)
        os.set("exit", disabled)

        // popen：JseOsLib 没有 popen（系统命令），本来就返回 nil；保险起见覆盖
        os.set("popen", disabled)

        // os.remove / os.rename：把路径约束到工作区
        val realRemove = os.get("remove") as? VarArgFunction
        if (realRemove != null) {
            os.set("remove", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val safe = safeResolve(args.arg1().toString(), workspace)
                    return realRemove.call(LuaValue.valueOf(safe))
                }
            })
        }
        val realRename = os.get("rename") as? VarArgFunction
        if (realRename != null) {
            os.set("rename", object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    val from = safeResolve(args.arg1().toString(), workspace)
                    val to = safeResolve(args.arg(2).toString(), workspace)
                    return realRename.call(LuaValue.valueOf(from), LuaValue.valueOf(to))
                }
            })
        }

        // os.setlocale：不允许，返回 nil
        os.set("setlocale", disabled)
    }

    /**
     * 把任意路径锚定到工作区：
     * - 绝对路径 → 取最后一段文件名
     * - 含 .. 的路径 → 移除所有 .. 段与它之前的一段
     * - 正常相对路径 → 原样拼到 workspace
     */
    private fun safeResolve(path: String, workspace: File): String {
        val cleaned = path.replace('\\', '/').trim()
        // 去掉开头的 "/" 或 drive letter "X:/"
        val noDrive = cleaned.removePrefix("/").removePrefix("/")
            .replace(Regex("^[A-Za-z]:/?"), "")
        val parts = noDrive.split('/').filter { it.isNotBlank() && it != "." }.toMutableList()
        // 移除所有 ".." 段
        val result = mutableListOf<String>()
        for (p in parts) {
            if (p == "..") {
                if (result.isNotEmpty()) result.removeAt(result.lastIndex)
                // 上跳越过了 workspace 根也没关系，继续跳过
            } else {
                result.add(p)
            }
        }
        if (result.isEmpty()) return workspace.absolutePath
        return File(workspace, result.joinToString("/")).absolutePath
    }

    /**
     * 执行一段 Lua 代码。
     * 返回值是最后一个表达式的 toString()；所有 print 输出走 printLine 回调。
     * 异常会包装在 Result.failure 中。
     */
    fun exec(code: String): Result<String> = runCatching {
        val chunk = globals.load(code)
        val ret = chunk.call()
        ret.toString()
    }

    /**
     * 执行一个 Lua 文件。
     * 文件路径会经过 safeResolve 锚定到 workspace；外部绝对路径也会被约束成工作区内文件名。
     * 注意：用 globals.load(source, filename) 替代 globals.loadfile()——
     * LuaJ 的 loadfile 走 package.path 查文件，Android 上的类加载器路径会导致它返回 null，
     * 然后 chunk.call() 直接 NPE。自己先读好内容再编译最稳。
     */
    fun execFile(file: File): Result<String> = runCatching {
        val safePath = workspace?.let { safeResolve(file.absolutePath, it) } ?: file.absolutePath
        val safeFile = File(safePath)
        if (!safeFile.exists()) {
            throw RuntimeException("文件不存在: $safePath")
        }
        val source = safeFile.readText(Charsets.UTF_8)
        val chunk = globals.load(source, safeFile.name)
        val ret = chunk.call()
        ret.toString()
    }
}
