# CLion Cpplint Nova Changelog

## [Unreleased]

## [1.0.2] - 2025-11-10

### Changed
- Updated plugin ID from com.github.meet-radek to com.github.meet-brad-ch
- Updated repository URL to https://github.com/meet-brad-ch/CLion-cpplint-nova
- Updated GitHub username references throughout documentation

## [1.0.1] - 2025-11-10

### Fixed
- Removed "CLion" from plugin name to comply with JetBrains Marketplace requirements
- Fixed deprecated API usage: replaced FileChooserDescriptorFactory with FileChooserDescriptor constructor
- Fixed deprecated API usage: replaced Project.baseDir with Project.basePath

## [1.0.0] - 2025-11-10

### Added
- Complete rewrite in Kotlin for CLion 2025.2 Nova compatibility
- Modern IntelliJ Platform Gradle Plugin 2.x architecture
- Works with both CLion Nova and CLion Classic
- Quick fixes for ending newline and header guards
- Configurable Python and cpplint paths via Settings

### Changed
- Based on original work by Hu Dong (itechbear)
- Migrated from Java to Kotlin
- Updated to modern plugin development practices
