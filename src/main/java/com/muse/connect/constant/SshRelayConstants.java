package com.muse.connect.constant;

import java.util.List;

/**
 * SSH / SFTP 中转通道、安全策略、活动记录与响应字段常量类。
 */
public final class SshRelayConstants {

    private SshRelayConstants() {
        throw new UnsupportedOperationException("常量类禁止实例化");
    }

    /** SSH 命令执行通道类型 */
    public static final String CHANNEL_EXEC = "exec";
    /** SFTP 文件传输通道类型 */
    public static final String CHANNEL_SFTP = "sftp";
    /** JSch 严格主机密钥校验配置项名 */
    public static final String CONFIG_STRICT_HOST_KEY_CHECKING = "StrictHostKeyChecking";
    /** JSch 配置启用值 */
    public static final String VALUE_YES = "yes";
    /** JSch 配置关闭值 */
    public static final String VALUE_NO = "no";
    /** JSch 内存私钥标识前缀 */
    public static final String IDENTITY_NAME_PREFIX = "muse-key-";

    /** SSH 连通性测试命令 */
    public static final String TEST_COMMAND = "echo muse-ssh-ok";
    /** 命令白名单分隔符 */
    public static final String ALLOWLIST_DELIMITER = ",";
    /** 启用命令白名单时禁止出现的 Shell 链式与子命令操作符 */
    public static final List<String> BLOCKED_CHAINING_TOKENS = List.of(
            ";", "&&", "||", "|", "`", "$(", "\n", "\r"
    );

    /** 响应字段：远程文件路径 */
    public static final String FIELD_REMOTE_PATH = "remotePath";
    /** 响应字段：已读取字节数 */
    public static final String FIELD_BYTES_READ = "bytesRead";
    /** 响应字段：已写入字节数 */
    public static final String FIELD_BYTES_WRITTEN = "bytesWritten";
    /** 响应字段：文件内容 */
    public static final String FIELD_CONTENT = "content";
    /** 响应字段：是否追加模式 */
    public static final String FIELD_APPEND = "append";
    /** 响应字段：命令退出码 */
    public static final String FIELD_EXIT_CODE = "exitCode";
    /** 响应字段：标准输出 */
    public static final String FIELD_STDOUT = "stdout";
    /** 响应字段：标准错误输出 */
    public static final String FIELD_STDERR = "stderr";

    /** 活动流水动作前缀：SFTP 读取 */
    public static final String ACTION_SFTP_READ_PREFIX = "sftp:read ";
    /** 活动流水动作前缀：SFTP 写入 */
    public static final String ACTION_SFTP_WRITE_PREFIX = "sftp:write ";
    /** 活动流水动作前缀：SSH 命令执行 */
    public static final String ACTION_EXEC_PREFIX = "exec: ";
    /** 活动流水摘要前缀：字节数 */
    public static final String SUMMARY_BYTES_PREFIX = "bytes=";
    /** 活动流水摘要前缀：退出码 */
    public static final String SUMMARY_EXIT_PREFIX = "exit=";
}
