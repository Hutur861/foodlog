package com.example.foodlog.client;

import com.example.foodlog.FoodRegistryUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Viewer for the locally stored snapshot. All data lives on this client, so the screen
 * works on any server, with or without the mod installed server side.
 *
 * Foods can be grouped by several dimensions; the tab strip shows one tab per bucket with
 * the number of matching foods, which makes "what have I still not eaten" answerable at a
 * glance (e.g. 7 kinds of fish left). Tabs that do not fit are reachable through the pager
 * arrows on the right, and the source dimension gets an extra row because a modpack can
 * easily have dozens of namespaces.
 */
public class FoodLogScreen extends Screen {

    private static final int CELL_WIDTH = 132;
    private static final int CELL_HEIGHT = 22;
    private static final int SEARCH_Y = 18;
    private static final int DIMENSION_Y = 39;
    private static final int TABS_Y = 55;
    private static final int TAB_ROW_HEIGHT = 14;
    private static final int TAB_GAP = 3;
    private static final int PAGER_WIDTH = 34;
    private static final int GRID_BOTTOM_MARGIN = 34;
    private static final int COLOR_EATEN = 0xFF55FF55;
    private static final int COLOR_IMPORTED = 0xFF55FFFF;
    private static final int COLOR_UNEATEN = 0xFF9E9E9E;
    private static final int COLOR_TAB_ACTIVE = 0xFFFFFFFF;
    private static final int COLOR_TAB_IDLE = 0xFF9E9E9E;
    private static final int COLOR_TAB_BACKGROUND = 0x80404040;
    private static final int COLOR_PAGER_ACTIVE = 0xFFDDDDDD;
    private static final int COLOR_PAGER_IDLE = 0xFF555555;

    private enum Filter {
        ALL("foodlog.filter.all"),
        EATEN("foodlog.filter.eaten"),
        UNEATEN("foodlog.filter.uneaten");

        private final String translationKey;

        Filter(String translationKey) {
            this.translationKey = translationKey;
        }

        public Component label() {
            return Component.translatable(this.translationKey);
        }
    }

    /** A tab with its position on the current page. */
    private record Tab(String key, Component label, int count, int x, int y, int width) {
    }

    private EditBox searchBox;
    private Filter filter = Filter.ALL;
    private FoodCategories.Dimension dimension = FoodCategories.Dimension.TYPE;
    private String selectedCategory;
    private List<ResourceLocation> visible = List.of();
    private List<List<Tab>> pages = List.of(List.of());
    private int tabPage;
    private int scroll;
    private int columns = 1;
    private int lastVersion = -1;
    private int eatenCount;
    private int totalCount;

    public FoodLogScreen() {
        super(Component.translatable("foodlog.screen.title"));
    }

    @Override
    protected void init() {
        this.columns = Math.max(1, (this.width - 28) / CELL_WIDTH);

        this.searchBox = new EditBox(this.font, 12, SEARCH_Y, 160, 18,
                Component.translatable("foodlog.screen.title"));
        this.searchBox.setResponder(text -> rebuild());
        addRenderableWidget(this.searchBox);

        int dimensionX = 12;
        for (FoodCategories.Dimension value : FoodCategories.Dimension.values()) {
            addRenderableWidget(Button.builder(value.label(), button -> {
                this.dimension = value;
                this.selectedCategory = null;
                this.tabPage = 0;
                this.scroll = 0;
                rebuild();
            }).bounds(dimensionX, DIMENSION_Y, 38, 14).build());
            dimensionX += 40;
        }

        int buttonY = this.height - 26;
        int buttonX = 12;
        for (Filter value : Filter.values()) {
            addRenderableWidget(Button.builder(value.label(), button -> {
                this.filter = value;
                this.tabPage = 0;
                this.scroll = 0;
                rebuild();
            }).bounds(buttonX, buttonY, 72, 20).build());
            buttonX += 76;
        }

        addRenderableWidget(Button.builder(Component.translatable("foodlog.button.import"), button -> {
            // The server answers with the full statistic table a few ticks from now, at which
            // point the screen picks the result up through the data version check.
            ClientEvents.requestImport();
        }).bounds(this.width - 112, 15, 100, 18).build());

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(this.width - 72, buttonY, 60, 20)
                .build());

        rebuild();
    }

    private void rebuild() {
        String query = this.searchBox == null ? "" : this.searchBox.getValue().trim().toLowerCase();
        List<ResourceLocation> pool = new ArrayList<>();
        int eaten = 0;
        int total = 0;

        for (ResourceLocation id : FoodRegistryUtil.getAllFoodIds()) {
            boolean isEaten = ClientFoodLogData.isEaten(id);
            total++;
            if (isEaten) {
                eaten++;
            }
            if (this.filter == Filter.EATEN && !isEaten) {
                continue;
            }
            if (this.filter == Filter.UNEATEN && isEaten) {
                continue;
            }
            if (!query.isEmpty() && !matches(id, query)) {
                continue;
            }
            pool.add(id);
        }
        this.eatenCount = eaten;
        this.totalCount = total;

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ResourceLocation id : pool) {
            counts.merge(FoodCategories.keyOf(id, this.dimension), 1, Integer::sum);
        }
        if (this.selectedCategory != null && !counts.containsKey(this.selectedCategory)) {
            this.selectedCategory = null;
        }

        this.pages = paginate(buildEntries(counts, pool.size()));
        this.tabPage = Math.max(0, Math.min(this.tabPage, this.pages.size() - 1));

        if (this.selectedCategory == null) {
            this.visible = pool;
        } else {
            List<ResourceLocation> selected = new ArrayList<>();
            for (ResourceLocation id : pool) {
                if (this.selectedCategory.equals(FoodCategories.keyOf(id, this.dimension))) {
                    selected.add(id);
                }
            }
            this.visible = selected;
        }
        clampScroll();
    }

    private List<Tab> buildEntries(Map<String, Integer> counts, int totalInPool) {
        List<Tab> entries = new ArrayList<>();
        int maxWidth = this.width - 12 - PAGER_WIDTH - 12;

        addEntry(entries, null, Component.translatable("foodlog.cat.all"), totalInPool, maxWidth);
        for (String key : FoodCategories.sortedKeys(this.dimension, counts.keySet())) {
            addEntry(entries, key, FoodCategories.labelOf(key, this.dimension),
                    counts.getOrDefault(key, 0), maxWidth);
        }
        return entries;
    }

    private void addEntry(List<Tab> entries, String key, Component label, int count, int maxWidth) {
        int width = Math.min(this.font.width(label.getString() + " " + count) + 10, maxWidth);
        entries.add(new Tab(key, label, count, 0, 0, width));
    }

    /**
     * Fills the available tab rows left to right; whatever does not fit starts a new page so
     * no category is ever dropped.
     */
    private List<List<Tab>> paginate(List<Tab> entries) {
        List<List<Tab>> result = new ArrayList<>();
        int maxX = this.width - 12 - PAGER_WIDTH;
        int rows = tabRows();
        int index = 0;

        while (index < entries.size()) {
            List<Tab> page = new ArrayList<>();
            int x = 12;
            int row = 0;
            while (index < entries.size()) {
                Tab entry = entries.get(index);
                if (x + entry.width() > maxX) {
                    if (row + 1 >= rows) {
                        break;
                    }
                    row++;
                    x = 12;
                }
                page.add(new Tab(entry.key(), entry.label(), entry.count(), x, TABS_Y + row * TAB_ROW_HEIGHT,
                        entry.width()));
                x += entry.width() + TAB_GAP;
                index++;
            }
            if (page.isEmpty()) {
                break;
            }
            result.add(page);
        }
        if (result.isEmpty()) {
            result.add(List.of());
        }
        return result;
    }

    /** The source dimension gets one extra row, so a modpack's namespaces are easier to scan. */
    private int tabRows() {
        int desired = this.dimension == FoodCategories.Dimension.SOURCE ? 3 : 2;
        int space = this.height - GRID_BOTTOM_MARGIN - CELL_HEIGHT - (TABS_Y + TAB_GAP);
        return Math.min(desired, Math.max(1, space / TAB_ROW_HEIGHT));
    }

    private int gridTop() {
        return TABS_Y + tabRows() * TAB_ROW_HEIGHT + TAB_GAP;
    }

    private List<Tab> currentPage() {
        int index = Math.max(0, Math.min(this.tabPage, this.pages.size() - 1));
        return this.pages.get(index);
    }

    private boolean hasPages() {
        return this.pages.size() > 1;
    }

    private int pagerX() {
        return this.width - 12 - PAGER_WIDTH;
    }

    private int pagerY() {
        return TABS_Y + (tabRows() * TAB_ROW_HEIGHT) / 2 - 4;
    }

    private boolean matches(ResourceLocation id, String query) {
        if (id.toString().toLowerCase().contains(query)) {
            return true;
        }
        return FoodCategories.searchNameOf(id).contains(query);
    }

    private int rowsPerPage() {
        int available = this.height - GRID_BOTTOM_MARGIN - gridTop();
        return Math.max(1, available / CELL_HEIGHT);
    }

    private int maxScroll() {
        int totalRows = (this.visible.size() + this.columns - 1) / this.columns;
        return Math.max(0, totalRows - rowsPerPage());
    }

    private void clampScroll() {
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.lastVersion != ClientFoodLogData.getVersion()) {
            this.lastVersion = ClientFoodLogData.getVersion();
            rebuild();
        }

        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.title, 12, 6, 0xFFFFFF, false);

        Component progress = Component.translatable("foodlog.progress", this.eatenCount, this.totalCount);
        graphics.drawString(this.font, progress, this.width - 12 - this.font.width(progress), 6, 0xA0A0A0, false);

        int activeDimensionX = 12 + this.dimension.ordinal() * 40;
        graphics.fill(activeDimensionX, DIMENSION_Y + 14, activeDimensionX + 38, DIMENSION_Y + 15, 0xFFFFCC44);

        renderTabs(graphics, mouseX, mouseY);
        renderPager(graphics, mouseX, mouseY);

        int gridTop = gridTop();
        if (this.visible.isEmpty()) {
            int y = gridTop + (this.height - GRID_BOTTOM_MARGIN - gridTop) / 2;
            String text = Component.translatable("foodlog.empty").getString();
            graphics.drawString(this.font, text, (this.width - this.font.width(text)) / 2, y, 0xFFAAAAAA, false);
            return;
        }

        int availableWidth = this.width - 28;
        this.columns = Math.max(1, availableWidth / CELL_WIDTH);
        clampScroll();

        int gridWidth = this.columns * CELL_WIDTH;
        int startX = (this.width - gridWidth) / 2 + 2;
        int firstIndex = this.scroll * this.columns;
        int lastIndex = Math.min(this.visible.size(), firstIndex + rowsPerPage() * this.columns);

        graphics.enableScissor(0, gridTop - 2, this.width, this.height - GRID_BOTTOM_MARGIN + 2);
        ResourceLocation hovered = null;
        for (int index = firstIndex; index < lastIndex; index++) {
            int position = index - firstIndex;
            int column = position % this.columns;
            int row = position / this.columns;
            int cellX = startX + column * CELL_WIDTH;
            int cellY = gridTop + row * CELL_HEIGHT;

            ResourceLocation id = this.visible.get(index);
            ItemStack stack = FoodCategories.stackOf(id);
            boolean eaten = ClientFoodLogData.isEaten(id);
            boolean imported = ClientFoodLogData.isImported(id);

            graphics.renderItem(stack, cellX, cellY + 2);

            String clipped = this.font.plainSubstrByWidth(FoodCategories.nameOf(id), CELL_WIDTH - 30);
            graphics.drawString(this.font, clipped, cellX + 22, cellY + 7,
                    !eaten ? COLOR_UNEATEN : (imported ? COLOR_IMPORTED : COLOR_EATEN), false);

            if (mouseX >= cellX && mouseX < cellX + CELL_WIDTH - 8
                    && mouseY >= cellY && mouseY < cellY + CELL_HEIGHT) {
                hovered = id;
            }
        }
        graphics.disableScissor();

        if (maxScroll() > 0) {
            Component hint = Component.translatable("foodlog.scroll", this.scroll + 1, maxScroll() + 1)
                    .withStyle(ChatFormatting.DARK_GRAY);
            graphics.drawString(this.font, hint, this.width - 12 - this.font.width(hint),
                    this.height - GRID_BOTTOM_MARGIN - 10, 0xFFFFFF, false);
        }

        if (hovered != null) {
            boolean eaten = ClientFoodLogData.isEaten(hovered);
            boolean imported = ClientFoodLogData.isImported(hovered);
            ItemStack stack = FoodCategories.stackOf(hovered);
            List<Component> lines = new ArrayList<>();
            lines.add(stack.isEmpty() ? Component.literal(hovered.toString()) : stack.getHoverName());
            lines.add(Component.translatable(eaten ? "foodlog.filter.eaten" : "foodlog.filter.uneaten")
                    .withStyle(imported ? ChatFormatting.AQUA
                            : (eaten ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
            if (imported) {
                lines.add(Component.translatable("foodlog.info.imported").withStyle(ChatFormatting.DARK_AQUA));
            }
            lines.add(FoodCategories.labelOf(FoodCategories.keyOf(hovered, this.dimension), this.dimension)
                    .withStyle(ChatFormatting.AQUA));
            Component info = FoodCategories.describe(hovered);
            if (!info.getString().isEmpty()) {
                lines.add(info.copy().withStyle(ChatFormatting.DARK_GRAY));
            }
            graphics.renderComponentTooltip(this.font, lines, mouseX, mouseY);
        }
    }

    private void renderTabs(GuiGraphics graphics, int mouseX, int mouseY) {
        for (Tab tab : currentPage()) {
            boolean active = tab.key() == null
                    ? this.selectedCategory == null
                    : tab.key().equals(this.selectedCategory);
            boolean hovered = mouseX >= tab.x() && mouseX < tab.x() + tab.width()
                    && mouseY >= tab.y() && mouseY < tab.y() + TAB_ROW_HEIGHT - 2;

            if (active || hovered) {
                graphics.fill(tab.x() - 2, tab.y(), tab.x() + tab.width(), tab.y() + TAB_ROW_HEIGHT - 2,
                        COLOR_TAB_BACKGROUND);
            }
            String text = tab.label().getString() + " " + tab.count();
            graphics.drawString(this.font, text, tab.x() + 3, tab.y() + 3,
                    active || hovered ? COLOR_TAB_ACTIVE : COLOR_TAB_IDLE, false);
        }
    }

    private void renderPager(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!hasPages()) {
            return;
        }
        int y = pagerY();
        int x = pagerX();

        boolean backActive = this.tabPage > 0;
        boolean forwardActive = this.tabPage < this.pages.size() - 1;
        boolean backHovered = backActive && isOverPager(mouseX, mouseY, x);
        boolean forwardHovered = forwardActive && isOverPager(mouseX, mouseY, x + 16);

        graphics.drawString(this.font, "<", x + 3, y, backActive ? COLOR_PAGER_ACTIVE : COLOR_PAGER_IDLE, false);
        graphics.drawString(this.font, ">", x + 19, y, forwardActive ? COLOR_PAGER_ACTIVE : COLOR_PAGER_IDLE, false);

        Component page = Component.translatable("foodlog.tab.page", this.tabPage + 1, this.pages.size());
        graphics.drawString(this.font, page, x, y + 11, backHovered || forwardHovered ? 0xFFFFFF : 0x777777, false);
    }

    private boolean isOverPager(double mouseX, double mouseY, int pagerX) {
        return mouseX >= pagerX && mouseX < pagerX + 14
                && mouseY >= pagerY() - 2 && mouseY < pagerY() + 8;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            if (hasPages()) {
                if (isOverPager(mouseX, mouseY, pagerX()) && this.tabPage > 0) {
                    this.tabPage--;
                    return true;
                }
                if (isOverPager(mouseX, mouseY, pagerX() + 16) && this.tabPage < this.pages.size() - 1) {
                    this.tabPage++;
                    return true;
                }
            }
            for (Tab tab : currentPage()) {
                if (mouseX >= tab.x() - 2 && mouseX < tab.x() + tab.width()
                        && mouseY >= tab.y() && mouseY < tab.y() + TAB_ROW_HEIGHT - 2) {
                    this.selectedCategory = tab.key();
                    this.scroll = 0;
                    rebuild();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (maxScroll() > 0 && mouseY > gridTop() && mouseY < this.height - GRID_BOTTOM_MARGIN) {
            this.scroll -= (int) Math.signum(delta);
            clampScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}