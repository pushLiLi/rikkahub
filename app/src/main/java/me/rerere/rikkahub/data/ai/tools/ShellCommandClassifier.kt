package me.rerere.rikkahub.data.ai.tools

/**
 * Shell 命令的静态安全级别，供工作区智能审批（SMART 策略）与 Plan 模式判断放行。
 */
enum class ShellCommandSafety {
    /** 只读命令：无文件写入、无环境变更 */
    SAFE_READ_ONLY,

    /** 工作区内常规修改：写文件、构建、测试、本地 git 操作等 */
    SAFE_WORKSPACE_MUTATION,

    /** 高危命令：删除、系统级修改、网络传输、全局包安装等，需要用户审批 */
    HIGH_RISK,
}

/**
 * 对 workspace_shell 的命令做纯静态安全分级（不执行命令）。
 *
 * 分析流程：
 * - 按引号外的 `;` `&&` `||` `|` `&` 换行拆成子命令，整条命令取最不安全的一档；
 * - 对每个子命令做引号感知的分词，识别重定向目标（`>` `>>` `2>`，`/dev/null` 与 fd 复制除外）、
 *   命令替换（`$(` `` ` ``）与 `VAR=val` 前缀；
 * - 按命令名查白名单/黑名单；git、npm 等带子命令的再细分；
 * - 处于 [SAFE_WORKSPACE_MUTATION] 档的命令，若重定向或写入目标（最后一个路径参数）落在
 *   [safeRoots] 之外，升级为 [HIGH_RISK]。
 *
 * 未知命令一律按 [HIGH_RISK] 处理：智能放行的语义是"确定的才放行，不确定就问"。
 */
object ShellCommandClassifier {

    /** 下载并直接交给 shell 执行的模式，单独立案 */
    private val PIPE_TO_SHELL = Regex(
        """\b(curl|wget)\b[^\n|;]*\|\s*(sudo\s+)?(sh|bash|zsh|dash|ksh|ash)\b""",
    )

    /** 跳过这些前缀命令后，再分析真正的命令 */
    private val COMMAND_PREFIXES = setOf("time", "nohup", "command", "builtin")

    /** 纯查看类命令，参数无关（重定向/命令替换另行升级） */
    private val READ_ONLY_COMMANDS = setOf(
        // 文件与目录查看
        "ls", "cat", "head", "tail", "less", "more", "pwd", "tree", "file", "stat",
        "du", "df", "wc", "md5sum", "sha1sum", "sha256sum", "sha512sum", "cksum",
        "realpath", "dirname", "basename", "readlink", "lsattr",
        // 文本搜索与处理
        "grep", "egrep", "fgrep", "rg", "ag", "ack", "awk", "gawk", "cut", "sort", "uniq",
        "tr", "column", "col", "jq", "yq", "diff", "comm", "cmp", "nl", "strings",
        "od", "xxd", "hexdump", "base64", "bc", "expr", "iconv", "fold", "fmt", "expand",
        // 环境与状态
        "echo", "printf", "env", "printenv", "which", "whereis", "type", "whoami", "id",
        "uname", "hostname", "hostnamectl", "date", "cal", "uptime", "ps", "free", "lscpu",
        "lsblk", "lsof", "groups", "history", "true", "false", "test", "[", "[[",
        "getent", "host", "nslookup", "dig", "ping", "ping6", "sleep", "seq", "wait",
        "jobs", "disown", "journalctl", "dmesg", "vmstat", "iostat", "yes", "rev", "tac",
        // shell 内置（无文件副作用）
        "cd", "pushd", "popd", "dirs", "export", "unset", "set", "shift", "shopt",
        "alias", "unalias", "umask", "readonly", "local", "return", "exit", "break", "continue",
    )

    /** 工作区内常规修改：写文件、构建、测试、本地版本控制 */
    private val WORKSPACE_MUTATION_COMMANDS = setOf(
        // 常规文件操作
        "mkdir", "touch", "cp", "mv", "ln", "tee", "split", "cpio", "install", "patch",
        "tar", "unzip", "zip", "gunzip", "gzip", "bzip2", "xz", "zstd", "7z",
        // 构建与测试
        "make", "cmake", "ninja", "meson", "gradle", "mvn", "sbt", "bazel", "buck",
        "pytest", "py.test", "unittest", "jest", "vitest", "mocha", "karma", "junit",
        "tsc", "eslint", "biome", "prettier", "stylelint", "black", "ruff", "isort",
        "autopep8", "gofmt", "rustfmt", "clang-format", "clang-tidy", "shellcheck",
        "swig", "lex", "yacc", "bison", "flex",
        // 其他常规开发命令
        "source", ".",
    )

    /** 语言执行器：--version 等纯信息参数之外都是执行代码 */
    private val INTERPRETER_COMMANDS = setOf(
        "node", "deno", "bun", "python", "python3", "ruby", "perl", "php", "lua", "Rscript",
    )

    /** 高危命令：无论参数如何都要求审批 */
    private val HIGH_RISK_COMMANDS = setOf(
        // 删除与覆写
        "rm", "rmdir", "unlink", "shred", "dd", "mkfs", "mkfs.ext4", "mkfs.vfat", "fdisk",
        "sfdisk", "parted", "truncate", "sponge",
        // 权限与系统级修改
        "sudo", "su", "doas", "pkexec", "chmod", "chown", "chgrp", "chattr", "setfacl",
        "mount", "umount", "swapon", "swapoff", "sysctl", "modprobe", "insmod", "rmmod",
        "kill", "pkill", "killall", "fuser", "reboot", "shutdown", "halt", "poweroff",
        "init", "telinit",
        // 系统服务与计划任务
        "systemctl", "service", "crontab", "at", "batch",
        // 系统包管理器
        "apt", "apt-get", "apk", "pacman", "dnf", "yum", "zypper", "opkg", "brew",
        "snap", "flatpak", "nix", "guix",
        // 网络传输与远程访问
        "ssh", "scp", "sftp", "rsync", "nc", "netcat", "socat", "telnet", "ftp", "tftp",
        "iptables", "nftables", "ip", "iw", "iwconfig", "brctl", "ethtool",
        // 容器与虚拟化（影响宿主环境）
        "docker", "podman", "kubectl", "helm", "lxc", "virsh", "qemu-system-x86_64",
    )

    /** 这些命令的子命令需要细分（git/npm 等） */
    private val GIT_READ_ONLY = setOf(
        "status", "log", "diff", "show", "blame", "reflog", "shortlog", "describe",
        "rev-parse", "ls-files", "ls-remote", "cat-file", "symbolic-ref", "grep",
        "archive", "whatchanged", "verify-commit", "verify-tag", "count-objects",
    )
    private val GIT_HIGH_RISK = setOf("push", "reset", "clean", "rm", "gc", "filter-branch")
    private val GIT_MUTATION = setOf(
        "add", "commit", "checkout", "switch", "restore", "stash", "merge", "rebase",
        "cherry-pick", "revert", "am", "apply", "init", "clone", "fetch", "pull",
        "config", "remote", "branch", "tag", "worktree", "submodule", "bisect", "notes",
        "mv", "instaweb",
    )

    /** 只读的包管理器子命令 */
    private val PACKAGE_MANAGER_READ_ONLY = setOf(
        "ls", "list", "view", "info", "search", "outdated", "why", "whoami", "show",
        "freeze", "config", "get", "run-script-list", "help", "-v", "--version", "-V",
    )

    fun classify(command: String, safeRoots: List<String> = emptyList()): ShellCommandSafety {
        if (command.isBlank()) return ShellCommandSafety.SAFE_READ_ONLY
        // 下载内容直接管道给 shell 的模式整条按最高危处理
        if (PIPE_TO_SHELL.containsMatchIn(command)) return ShellCommandSafety.HIGH_RISK

        var worst = ShellCommandSafety.SAFE_READ_ONLY
        for (segment in splitSegments(command)) {
            val safety = classifySegment(segment, safeRoots)
            if (safety.ordinal > worst.ordinal) worst = safety
            if (worst == ShellCommandSafety.HIGH_RISK) break
        }
        return worst
    }

    // ---- 段拆分：引号外的 ; && || | & 与换行都是子命令边界 ----

    private fun splitSegments(command: String): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                }

                c == ';' || c == '|' || c == '\n' -> {
                    if (current.isNotBlank()) segments.add(current.toString())
                    current.clear()
                }

                c == '&' -> {
                    val next = command.getOrNull(i + 1)
                    // && 是分隔符；单个 & 后跟空白/结尾是后台执行，也是边界；
                    // 2>&1 这类 fd 复制里的 & 不拆
                    val isSeparator = next == '&' || next == null || next.isWhitespace() ||
                        next == ';' || next == '|'
                    if (isSeparator) {
                        if (current.isNotBlank()) segments.add(current.toString())
                        current.clear()
                        if (next == '&') i++
                    } else {
                        current.append(c)
                    }
                }

                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) segments.add(current.toString())
        return segments
    }

    // ---- 单个子命令分级 ----

    private fun classifySegment(segment: String, safeRoots: List<String>): ShellCommandSafety {
        val tokens = tokenize(segment)
        if (tokens.isEmpty()) return ShellCommandSafety.SAFE_READ_ONLY

        val hasCommandSubstitution = hasCommandSubstitution(segment)
        val redirectTargets = redirectFileTargets(tokens)

        var index = 0
        // 跳过 VAR=val 环境变量前缀与 time/nohup 等包裹命令
        while (index < tokens.size) {
            val text = tokens[index].text
            val isEnvAssignment = Regex("[A-Za-z_][A-Za-z0-9_]*=.*").matches(text)
            if (isEnvAssignment || text in COMMAND_PREFIXES) {
                index++
            } else {
                break
            }
        }
        val commandToken = tokens.getOrNull(index) ?: return ShellCommandSafety.SAFE_READ_ONLY
        val name = commandToken.text.substringAfterLast('/')
        val args = tokens.drop(index + 1).map { it.text }
        val flags = args.filter { it.startsWith("-") }

        val base = classifyByName(name, args, flags)

        // 只读命令一旦带命令替换或文件重定向，至少视为工作区修改
        var result = base
        if (base == ShellCommandSafety.SAFE_READ_ONLY &&
            (hasCommandSubstitution || redirectTargets.isNotEmpty())
        ) {
            result = ShellCommandSafety.SAFE_WORKSPACE_MUTATION
        }
        if (hasCommandSubstitution && base != ShellCommandSafety.SAFE_READ_ONLY) {
            // 危险命令里的命令替换可能隐藏任意内容，保持或升级
            result = ShellCommandSafety.HIGH_RISK
        }

        // 工作区修改类命令：写入目标必须落在安全区内
        if (result == ShellCommandSafety.SAFE_WORKSPACE_MUTATION && safeRoots.isNotEmpty()) {
            val writeTargets = buildList {
                addAll(redirectTargets)
                // cp/mv/ln/install 的最后一个路径参数是目标
                if (name in WRITE_TARGET_LAST_ARG_COMMANDS) {
                    args.lastOrNull { it.startsWith("/") }?.let(::add)
                }
            }
            if (writeTargets.any {
                    it !in ALWAYS_OK_PATHS &&
                        safeRoots.none { root -> it == root || it.startsWith("$root/") }
                }) {
                result = ShellCommandSafety.HIGH_RISK
            }
        }
        return result
    }

    private val WRITE_TARGET_LAST_ARG_COMMANDS = setOf("cp", "mv", "ln", "install", "tee")

    private val ALWAYS_OK_PATHS = setOf("/dev/null", "/dev/zero", "/dev/stdout", "/dev/stderr", "/dev/tty")

    private fun classifyByName(name: String, args: List<String>, flags: List<String>): ShellCommandSafety =
        when {
            name in HIGH_RISK_COMMANDS -> ShellCommandSafety.HIGH_RISK

            name == "git" -> classifyGit(args)

            name in setOf("npm", "pnpm", "yarn", "bun", "deno") ->
                classifyPackageManager(name, args, flags)

            name in setOf("pip", "pip3", "pipx", "uv", "poetry", "conda") ->
                classifyPip(args)

            name == "cargo" ->
                if (args.firstOrNull() in setOf("--version", "-V", "--list", "help", "locate-project", "metadata")) {
                    ShellCommandSafety.SAFE_READ_ONLY
                } else {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            name == "go" ->
                if (args.firstOrNull() in setOf("version", "doc", "help", "env")) {
                    ShellCommandSafety.SAFE_READ_ONLY
                } else {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            name == "sed" ->
                if (flags.any { it == "-i" || it == "--in-place" || it.startsWith("--in-place=") }) {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                } else {
                    ShellCommandSafety.SAFE_READ_ONLY
                }

            name == "find" ->
                if (args.any {
                        it == "-delete" || it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir"
                    }) {
                    ShellCommandSafety.HIGH_RISK
                } else {
                    ShellCommandSafety.SAFE_READ_ONLY
                }

            name == "curl" || name == "wget" || name == "aria2c" ->
                // 纯下载写入工作区；管道给 shell 的情况已在整条层面拦截
                ShellCommandSafety.SAFE_WORKSPACE_MUTATION

            name in INTERPRETER_COMMANDS ->
                // 只带版本/帮助参数是查询，其余是执行代码
                if (args.isNotEmpty() && args.all { it in INFO_FLAGS }) {
                    ShellCommandSafety.SAFE_READ_ONLY
                } else {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            name in READ_ONLY_COMMANDS -> ShellCommandSafety.SAFE_READ_ONLY

            name in WORKSPACE_MUTATION_COMMANDS -> ShellCommandSafety.SAFE_WORKSPACE_MUTATION

            name == "xargs" -> {
                // xargs 后面跟的才是真正要执行的命令
                val inner = args.firstOrNull { !it.startsWith("-") }
                if (inner == null) {
                    ShellCommandSafety.SAFE_READ_ONLY
                } else {
                    classifyByName(inner, args.drop(args.indexOf(inner) + 1), flags)
                }
            }

            else -> ShellCommandSafety.HIGH_RISK
        }

    private val INFO_FLAGS = setOf("-v", "-V", "--version", "--help", "-h", "-?", "--usage")

    private fun classifyGit(args: List<String>): ShellCommandSafety {
        val subcommand = args.firstOrNull()?.substringAfterLast('/') ?: return ShellCommandSafety.SAFE_READ_ONLY
        val rest = args.drop(1)
        return when {
            subcommand in GIT_HIGH_RISK -> ShellCommandSafety.HIGH_RISK

            subcommand == "branch" || subcommand == "symbolic-ref" ->
                if (rest.any {
                        it == "-d" || it == "-D" || it == "-m" || it == "-M" || it.startsWith("--delete") ||
                            it.startsWith("--edit-description")
                    }) {
                    ShellCommandSafety.HIGH_RISK
                } else {
                    ShellCommandSafety.SAFE_READ_ONLY
                }

            subcommand == "remote" ->
                if (rest.any { it in setOf("add", "remove", "rm", "rename", "set-url", "prune", "update") }) {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                } else {
                    ShellCommandSafety.SAFE_READ_ONLY
                }

            subcommand == "stash" ->
                // 无参数的 git stash 等价于 stash push
                if (rest.firstOrNull() in setOf("list", "show")) {
                    ShellCommandSafety.SAFE_READ_ONLY
                } else {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            subcommand == "config" ->
                if (rest.firstOrNull() in setOf("--get", "--get-all", "-l", "--list", "--get-regexp")) {
                    ShellCommandSafety.SAFE_READ_ONLY
                } else {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            subcommand in GIT_READ_ONLY -> ShellCommandSafety.SAFE_READ_ONLY

            subcommand in GIT_MUTATION -> ShellCommandSafety.SAFE_WORKSPACE_MUTATION

            else -> ShellCommandSafety.HIGH_RISK
        }
    }

    private fun classifyPackageManager(name: String, args: List<String>, flags: List<String>): ShellCommandSafety {
        val subcommand = args.firstOrNull() ?: return ShellCommandSafety.SAFE_READ_ONLY
        return when {
            subcommand in PACKAGE_MANAGER_READ_ONLY -> ShellCommandSafety.SAFE_READ_ONLY

            (subcommand == "install" || subcommand == "i" || subcommand == "add" || subcommand == "in") &&
                flags.any { it == "-g" || it == "--global" } -> ShellCommandSafety.HIGH_RISK

            subcommand in setOf("install", "i", "add", "in", "remove", "uninstall", "rm", "update",
                "upgrade", "up", "run", "test", "exec", "dlx", "create", "init", "link", "unlink",
                "publish", "unpublish", "ci", "pack", "prune", "dedupe", "audit", "fix", "patch",
                "build", "start", "dev", "bundle", "compile", "deploy", "outdated-check") ->
                ShellCommandSafety.SAFE_WORKSPACE_MUTATION

            // deno run / deno eval 直接执行代码
            name == "deno" && subcommand in setOf("run", "eval", "repl", "serve", "task", "install") ->
                if (subcommand ==
                    "install") {
                    ShellCommandSafety.HIGH_RISK
                } else {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            else -> ShellCommandSafety.SAFE_WORKSPACE_MUTATION
        }
    }

    private fun classifyPip(args: List<String>): ShellCommandSafety {
        val subcommand = args.firstOrNull() ?: return ShellCommandSafety.SAFE_READ_ONLY
        return when {
            subcommand in setOf("list", "show", "freeze", "index") -> ShellCommandSafety.SAFE_READ_ONLY

            subcommand == "install" || subcommand == "download" ->
                if (args.any { it == "--user" }) {
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                } else {
                    // pip 在 rootfs 内装包同样是修改执行环境，但属于常规开发操作
                    ShellCommandSafety.SAFE_WORKSPACE_MUTATION
                }

            subcommand == "uninstall" -> ShellCommandSafety.SAFE_WORKSPACE_MUTATION

            else -> ShellCommandSafety.SAFE_WORKSPACE_MUTATION
        }
    }

    // ---- 分词与重定向解析（引号感知） ----

    private class Token(val text: String, val quoted: Boolean)

    private fun tokenize(segment: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var quote: Char? = null
        var hasContent = false

        fun push() {
            if (hasContent) {
                tokens.add(Token(current.toString(), quoted = quote != null))
            }
            current.clear()
            hasContent = false
        }

        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                }

                c == '\'' || c == '"' -> {
                    quote = c
                    current.append(c)
                    hasContent = true
                }

                c.isWhitespace() -> push()

                c == '>' || (c.isDigit() && segment.getOrNull(i + 1) == '>') -> {
                    push()
                    val sb = StringBuilder()
                    if (c.isDigit()) {
                        sb.append(c)
                        i++ // 消费 fd 数字，现在 segment[i] 是 '>'
                    }
                    while (i < segment.length && segment[i] == '>') {
                        sb.append('>')
                        i++
                    }
                    if (i < segment.length && segment[i] == '&') {
                        sb.append('&')
                        i++
                        while (i < segment.length && segment[i].isDigit()) {
                            sb.append(segment[i])
                            i++
                        }
                        tokens.add(Token(sb.toString(), quoted = false))
                        continue
                    }
                    tokens.add(Token(sb.toString(), quoted = false))
                    i-- // 抵消外层 i++
                }

                else -> {
                    current.append(c)
                    hasContent = true
                }
            }
            i++
        }
        push()
        return tokens
    }

    /** 引号外的 `$(...)` 或反引号；`$((...))` 是算术扩展，不算命令替换 */
    private fun hasCommandSubstitution(segment: String): Boolean {
        var quote: Char? = null
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                quote != null -> if (c == quote) quote = null

                c == '\'' || c == '"' -> quote = c

                c == '$' && segment.getOrNull(i + 1) == '(' -> {
                    if (segment.getOrNull(i + 2) != '(') return true
                    i += 2 // 跳过 $(( ，内层内容继续扫描
                }

                c == '`' -> return true
            }
            i++
        }
        return false
    }

    /** 文件重定向（> >> 2> 2>>）的目标；/dev/null 与 fd 复制不算 */
    private fun redirectFileTargets(tokens: List<Token>): List<String> {
        val targets = mutableListOf<String>()
        for (i in tokens.indices) {
            val token = tokens[i]
            if (token.quoted) continue
            val isFileRedirect = token.text == ">" || token.text == ">>" ||
                token.text == "2>" || token.text == "2>>"
            if (isFileRedirect) {
                tokens.getOrNull(i + 1)?.let { next ->
                    val target = next.text.trim('\'', '"')
                    if (target != "/dev/null") targets.add(target)
                }
            }
        }
        return targets
    }
}
