# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Not applicable

### Changed
- Avoid crash on start up if database is not available when first RF emitter is processed.

### Removed
- Not applicable

## [1.0.6 - 2017-12-28]
### Added
- French translation. Thanks to @Massedil.

## [1.0.5 - 2017-12-24]
### Added
- Partial support for CDMA and WCDMA towers when using getAllCellInfo() API.

### Changed
- Check for unknown values in fields in the cell IDs returned by getAllCellInfo();

## [1.0.4 - 2017-12-18]
### Changed
- Add more checks for permissions not granted to avoid locking up phone.

## [1.0.3]
### Changed
- Correct blacklist logic

## [1.0.2]
### Changed
- Correct versionCode and versionName in gradle.build

## [1.0.1]
### Changed
- Corrected package ID in manifest

## [1.0.0]
### Added
- Initial Release
