package app.sprout.rewards.domain;

import java.util.List;
import java.util.Optional;

/** What points buy. The brands are fictional: the demo has no real partners. */
public final class Vault {

    public record Item(String code, String name, String brand, String kind, int points) {}

    public static final List<Item> ITEMS = List.of(
            new Item("THEME_MARIGOLD", "Marigold app theme", "Sprout", "THEME", 200),
            new Item("TREE_PLANTED", "A tree planted in your name", "Green Roots (fictional)", "DONATION", 300),
            new Item("MONSOON_CHAI_50", "₹50 at Monsoon Chai", "Monsoon Chai (fictional)", "VOUCHER", 500),
            new Item("CITY_METRO_100", "₹100 City Metro top-up", "City Metro (fictional)", "VOUCHER", 900),
            new Item("BOOKWORM_200", "₹200 at Bookworm Books", "Bookworm Books (fictional)", "VOUCHER", 1800));

    private Vault() {}

    public static Optional<Item> item(String code) {
        return ITEMS.stream().filter(i -> i.code().equals(code)).findFirst();
    }
}
