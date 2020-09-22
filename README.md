# Fileindex

Kotlin library to watch a directory for changes and index file contents.

# Features

Current and planned features:

- [X] Concurrent file watcher implementation.
- [X] Concurrent file loader implementation based on the file watcher that reloads a file on modification events.
- [X] Lock-free consistency guarantee that a file that is successfully loaded has not been written to while it was being read.
- [X] Event debouncing (firing fewer events for multiple changes to the same file)
- [ ] Better directory handling (copying, moving and removing entire directories currently shows problems)
- [ ] Adding and removing roots to the watcher and indexer without a full reindex. 
- [X] Lexer infrastructure that allows you to specify how files are to be tokenized for indexing. 
- [X] Indexing database that allows for efficient querying of words across all watched files.
- [X] Unit tests
- [X] Demo application

# Caveats

This uses kotlin coroutines, so make sure your Java and Kotlin distribution is current. It works for me on Java 14.0.2 and kotlin 1.4.10.

Please also note that since the default WatchService is very slow on MacOS. To alleviate this the library uses a flag from `com.sun.nio`, which is not portable across the different JVM implementations. 
