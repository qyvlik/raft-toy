package io.github.qyvlik.rafttoy.node;

public class ClientCommand {
    private String action;
    private String key;
    private String value;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "(" + action + " " + key + " = " + value + ")";
    }
}
