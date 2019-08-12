# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Added
- Not applicable

### Changed
- Not applicable

### Removed
- Not applicable

## [1.1.12] - 2019-08-12

### Changed
- Update gradle build environment.
- Add debug logging for detection of 5G WiFi/WLAN networds.
- Add some Czech, Austrian and Dutch transport WLANs to ignore list.

## [1.1.11] - 2019-04-21
### Added
- Add Esperanto and Polish translations

### Changed
- Update gradle build environment
- Revise list of WLAN/WiFi SSIDs to ignore

## [1.1.10] - 2018-12-18
### Added
- Ignore WLANs on trains and buses of transit agencies in southwest Sweden. Thanks to lbschenkel
- Ignore Austrian train WLANs. Thanks to akallabeth

### Changed
- Update Gradle build environment
- Revise checks for locations near lat/lon of 0,0

## [1.1.9] - 2018-09-06
### Added
- Chinese translation (thanks to @Crystal-RainSlide)
- Protect against external GPS giving locations near 0.0/0.0

## [1.1.8] - 2018-06-21
### Added
- Initial support for 5 GHz WLAN RF characteristics being different than 2.4 GHz WLAN. Note: 5GHz WLAN not tested on a real device.

### Changed
- Fix timing related crash on start up/shut down
- Revisions to better support external GPS with faster report rates.
- Revise database to allow same identifier on multiple emitter types.
- Updated build tools and target API version

## [1.1.7] - 2018-06-18
### Changed
- Fix crash on empty set of seen emitters.
- Fix some Lint identified items.

## [1.1.6] - 2018-06-17
### Added
- Add Ukrainian translation

### Changed
- Build specification to reduce size of released application.
- Update build environment

## [1.1.5] - 2018-03-19
### Added
- Russian Translation. Thanks to @bboa

## [1.1.4] - 2018-03-12
### Added
- German Translation. Thanks to @levush

## [1.1.3] - 2018-02-27

### Changed
- Protect against accessing null object.

## [1.1.2] - 2018-02-11

### Changed
- Fix typo in Polish strings. Thanks to @verdulo

## [1.1.1] - 2018-01-30
### Changed
- Refactor/clean up logic flow and position computation.

## [1.1.0] - 2018-01-25
### Changed
- Refactor RF emitter and database logic to allow for non-square coverage bounding boxes. Should result in more precise coverage mapping and thus better location estimation. Database file schema changed.

## [1.0.8] - 2018-01-12
### Added
- Polish Translation. Thanks to @verdulo

## [1.0.7] - 2018.01.05
### Changed
- Avoid crash on start up if database is not available when first RF emitter is processed.

## [1.0.6] - 2017-12-28
### Added
- French translation. Thanks to @Massedil.

## [1.0.5] - 2017-12-24
### Added
- Partial support for CDMA and WCDMA towers when using getAllCellInfo() API.

### Changed
- Check for unknown values in fields in the cell IDs returned by getAllCellInfo();

## [1.0.4] - 2017-12-18
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
