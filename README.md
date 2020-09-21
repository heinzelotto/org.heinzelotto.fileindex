# Fileindex

Kotlin library to watch a directory for changes and index file contents.

# Features

Current and planned features:

- [X] Concurrent file watcher implementation.
- [X] Concurrent file loader implementation based on the file watcher that reloads a file on modification events.
- [X] Lock-free consistency guarantee that if a file is loaded it has not been written to while it was being read.
- [ ] Event debouncing (firing less events for multiple changes to the same file)
- [X] Lexer infrastructure that allows you to specify how files are to be tokenized for indexing. 
- [X] Indexing database that allows for efficient querying of words across all watched files.
- [X] Unit tests
- [X] Demo application
