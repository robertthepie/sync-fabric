package dev.kir.sync.compat;

import com.neep.neepmeat.api.theme.GuideCols;
import com.neep.neepmeat.documentation.DocumentationReloadListener;
import com.neep.neepmeat.guide.GuideNode;
import com.neep.neepmeat.guide.GuideReloadEvent;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashSet;
import java.util.Set;

public class NeepArticles {
    public static final DocumentationReloadListener SYNC_WORD_DOCS = new DocumentationReloadListener("sync");

    public static void init() {
        GuideReloadEvent.POST.register(guideReloadListener -> {
            GuideNode root = guideReloadListener.getRootNode();

            if (root == null) return;

            // basic shell article node -- node as in the clickable button, article as in a node with no sub nodes
            Set<String> lookup = new HashSet<>();
            lookup.add("Sync:shell_storage");
            lookup.add("Sync:shell_constructor");
            GuideNode.ArticleNode node = new GuideNode.ArticleNode(
                    "sync_shell",
                    lookup,
                    new Identifier("minecraft:candle"),
                    Text.of(
                            "Player Shells"
                    ).copy().setStyle(Style.EMPTY.withFont(GuideCols.FONT)));
            root.addChild(node);
        });
    }
}
