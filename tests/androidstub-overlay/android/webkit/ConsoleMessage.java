package android.webkit;

/** 最小 ConsoleMessage 替身：只暴露类型/行号/来源，用于去敏诊断断言。 */
public class ConsoleMessage {

    public enum MessageLevel {
        TIP,
        LOG,
        WARNING,
        ERROR,
        DEBUG
    }

    private final MessageLevel level;
    private final String message;
    private final String sourceId;
    private final int lineNumber;

    public ConsoleMessage(MessageLevel level, String message, String sourceId, int lineNumber) {
        this.level = level;
        this.message = message;
        this.sourceId = sourceId;
        this.lineNumber = lineNumber;
    }

    public MessageLevel messageLevel() {
        return level;
    }

    public String message() {
        return message;
    }

    public String sourceId() {
        return sourceId;
    }

    public int lineNumber() {
        return lineNumber;
    }
}
