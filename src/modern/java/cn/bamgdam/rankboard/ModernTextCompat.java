package cn.bamgdam.rankboard;

import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import java.net.URI;

final class TextCompat {
    private TextCompat() { }

    static Style interactive(Style style, String command, Text hoverText) {
        return style.withClickEvent(new ClickEvent.RunCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverText));
    }

    static Style suggest(Style style, String command, Text hoverText) {
        return style.withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(hoverText));
    }

    static Style openUrl(Style style, String url, Text hoverText) {
        return style.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                .withHoverEvent(new HoverEvent.ShowText(hoverText));
    }
}
