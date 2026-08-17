/** Holds the dependencies shared by HTTP and HTTPS handlers. */
public final class ProxyContext {
    public final FilterStore filterStore;
    public final DiskCache cache;
    public final ClientLog log;
    public final boolean bonusLoginEnabled;

    public ProxyContext(FilterStore filterStore, DiskCache cache, ClientLog log,
                        boolean bonusLoginEnabled) {
        this.filterStore = filterStore;
        this.cache = cache;
        this.log = log;
        this.bonusLoginEnabled = bonusLoginEnabled;
    }
}
