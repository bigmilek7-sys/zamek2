package pl.zamek;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZamekPlugin extends JavaPlugin implements Listener {

    // ====== USTAWIENIA - zmien tutaj, jesli maja byc inne ======
    private static final int INNER_W = 6;                    // szerokosc/dlugosc w srodku
    private static final int INNER_H = 5;                    // wysokosc w srodku
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;  // cooldown: 3 minuty
    private static final long LIFETIME_TICKS = 10 * 20L;     // zamek znika po 10 sekundach
    private static final Material WALL = Material.BLACKSTONE;
    // ============================================================

    private static final int OUTER_W = INNER_W + 2;          // razem ze scianami (8)
    private static final int OUTER_H = INNER_H + 2;          // razem z podloga i sufitem (7)

    private NamespacedKey key;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final List<Castle> castles = new ArrayList<>();

    /** Postawiony zamek: zapamietuje, co bylo wczesniej, zeby to przywrocic. */
    private static final class Castle {
        final World world;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final List<Block> blocks = new ArrayList<>();
        final List<BlockData> originals = new ArrayList<>();

        Castle(World world, int minX, int minY, int minZ) {
            this.world = world;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = minX + OUTER_W - 1;
            this.maxY = minY + OUTER_H - 1;
            this.maxZ = minZ + OUTER_W - 1;
        }

        boolean overlaps(Castle o) {
            return world.equals(o.world)
                    && minX <= o.maxX && maxX >= o.minX
                    && minY <= o.maxY && maxY >= o.minY
                    && minZ <= o.maxZ && maxZ >= o.minZ;
        }

        /** Czy blok jest czescia sciany/podlogi/sufitu zamku. */
        boolean isShell(Block b) {
            if (!b.getWorld().equals(world)) return false;
            int x = b.getX(), y = b.getY(), z = b.getZ();
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return false;
            return x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
        }
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "zamek");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // przy wylaczeniu serwera przywracamy teren
        for (Castle c : new ArrayList<>(castles)) {
            remove(c);
        }
    }

    // ---------- komenda /zamek [gracz] ----------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Nie ma takiego gracza online.", NamedTextColor.RED));
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(Component.text("Uzycie: /zamek <gracz>", NamedTextColor.RED));
            return true;
        }
        target.getInventory().addItem(createItem()).values()
                .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
        sender.sendMessage(Component.text("Dano przedmiot Zamek graczowi " + target.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.DEEPSLATE_BRICKS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Zamek", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Kliknij prawym przyciskiem,", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("aby postawic zamek wokol siebie.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // ---------- klikniecie przedmiotem (w blok albo w powietrze) ----------
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Action action = e.getAction();
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) return;

        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta()) return;
        if (!item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("zamek.use")) {
            p.sendMessage(Component.text("Nie masz uprawnien.", NamedTextColor.RED));
            return;
        }

        // cooldown (dziala na wszystkich, takze na OP)
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            long left = (COOLDOWN_MS - (now - last) + 999) / 1000;
            p.sendMessage(Component.text("Zamek mozesz postawic za " + (left / 60) + "m " + (left % 60) + "s.", NamedTextColor.RED));
            return;
        }

        // zamek staje tam, gdzie stoi gracz: podloga w bloku pod nogami, gracz w srodku
        World w = p.getWorld();
        int half = OUTER_W / 2 - 1;
        int minX = p.getLocation().getBlockX() - half;
        int minZ = p.getLocation().getBlockZ() - half;
        int minY = p.getLocation().getBlockY() - 1;

        if (minY < w.getMinHeight() || minY + OUTER_H > w.getMaxHeight()) {
            p.sendMessage(Component.text("Tutaj zamek sie nie zmiesci (za nisko albo za wysoko).", NamedTextColor.RED));
            return;
        }

        Castle castle = new Castle(w, minX, minY, minZ);
        for (Castle other : castles) {
            if (castle.overlaps(other)) {
                p.sendMessage(Component.text("Tu juz stoi inny zamek.", NamedTextColor.RED));
                return;
            }
        }
        if (!build(castle)) {
            p.sendMessage(Component.text("W miejscu zamku jest skrzynia lub inny pojemnik, nie mozna postawic.", NamedTextColor.RED));
            return;
        }

        castles.add(castle);
        getServer().getScheduler().runTaskLater(this, () -> {
            if (castles.contains(castle)) remove(castle);
        }, LIFETIME_TICKS);

        cooldowns.put(p.getUniqueId(), now);
        if (p.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        p.sendMessage(Component.text("Postawiles zamek! Zniknie za 10 sekund.", NamedTextColor.GRAY));
    }

    // ---------- budowa i usuwanie ----------
    /** Stawia zamek. Zwraca false (i nic nie zmienia), jesli w srodku jest skrzynia/pojemnik. */
    private boolean build(Castle c) {
        World w = c.world;

        // sprawdzamy, czy nie zniszczymy pojemnika z zawartoscia
        for (int x = c.minX; x <= c.maxX; x++) {
            for (int y = c.minY; y <= c.maxY; y++) {
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    if (w.getBlockAt(x, y, z).getState() instanceof Container) return false;
                }
            }
        }

        for (int x = c.minX; x <= c.maxX; x++) {
            for (int y = c.minY; y <= c.maxY; y++) {
                for (int z = c.minZ; z <= c.maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    c.blocks.add(b);
                    c.originals.add(b.getBlockData());
                    boolean shell = x == c.minX || x == c.maxX
                            || y == c.minY || y == c.maxY
                            || z == c.minZ || z == c.maxZ;
                    b.setType(shell ? WALL : Material.AIR, false);
                }
            }
        }
        return true;
    }

    private void remove(Castle c) {
        castles.remove(c);
        for (int i = 0; i < c.blocks.size(); i++) {
            c.blocks.get(i).setBlockData(c.originals.get(i), false);
        }
    }

    // ---------- ochrona zamku ----------
    private boolean isProtected(Block b) {
        for (Castle c : castles) {
            if (c.isShell(b)) return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (isProtected(e.getBlock())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isProtected);
    }
}
