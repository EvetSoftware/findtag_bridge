import 'dart:async';

import 'package:flutter/services.dart';

enum FindTagEventType {
  bluetoothState,
  scanResult,
  scanFinished,
  operation,
  bleDiagnosticAdvertisement,
  bleDiagnosticScanFinished,
  bleDiagnosticNotification,
  bleDiagnosticListenStarted,
}

class FindTagException implements Exception {
  const FindTagException(this.code, this.message, {this.serverCode});

  final String code;
  final String message;
  final String? serverCode;

  factory FindTagException.fromPlatform(PlatformException error) =>
      FindTagException(
        error.code,
        error.message ?? 'Bilinmeyen FindTag hatası',
        serverCode: (error.details as Map?)?['serverCode'] as String?,
      );

  @override
  String toString() => message;
}

class FindTagEvent {
  const FindTagEvent(this.type, this.data);

  final FindTagEventType type;
  final Map<String, Object?> data;

  factory FindTagEvent.fromMap(Map<Object?, Object?> value) {
    final map = value.map((key, item) => MapEntry('$key', item));
    final type = switch (map['type']) {
      'bluetoothState' => FindTagEventType.bluetoothState,
      'scanResult' => FindTagEventType.scanResult,
      'scanFinished' => FindTagEventType.scanFinished,
      'bleDiagnosticAdvertisement' =>
        FindTagEventType.bleDiagnosticAdvertisement,
      'bleDiagnosticScanFinished' => FindTagEventType.bleDiagnosticScanFinished,
      'bleDiagnosticNotification' => FindTagEventType.bleDiagnosticNotification,
      'bleDiagnosticListenStarted' =>
        FindTagEventType.bleDiagnosticListenStarted,
      _ => FindTagEventType.operation,
    };
    return FindTagEvent(type, map);
  }
}

class ScannedTag {
  const ScannedTag({
    required this.id,
    this.name,
    required this.rssi,
    this.platformAddress,
    this.advertisedMac,
  });

  final String id;
  final String? name;
  final int rssi;
  final String? platformAddress;
  final String? advertisedMac;

  factory ScannedTag.fromMap(Map<String, Object?> map) => ScannedTag(
    id: map['id']! as String,
    name: map['name'] as String?,
    rssi: map['rssi'] as int,
    platformAddress: map['platformAddress'] as String?,
    advertisedMac: map['advertisedMac'] as String?,
  );
}

class SavedTag {
  const SavedTag({
    required this.id,
    required this.name,
    required this.maskedKey,
    required this.createdAt,
    this.platformAddress,
    this.advertisedMac,
  });

  final String id;
  final String name;
  final String maskedKey;
  final DateTime createdAt;
  final String? platformAddress;
  final String? advertisedMac;

  factory SavedTag.fromMap(Map<Object?, Object?> source) {
    final map = source.map((key, value) => MapEntry('$key', value));
    return SavedTag(
      id: map['id']! as String,
      name: map['name']! as String,
      maskedKey: map['maskedKey']! as String,
      createdAt: DateTime.fromMillisecondsSinceEpoch(
        map['createdAtMs']! as int,
      ),
      platformAddress: map['platformAddress'] as String?,
      advertisedMac: map['advertisedMac'] as String?,
    );
  }
}

class TagLocation {
  const TagLocation({
    required this.latitude,
    required this.longitude,
    required this.collectionTime,
    required this.queriedAt,
    required this.googleLocation,
    this.batteryPercent,
    this.accuracyMeters,
  });

  final double latitude;
  final double longitude;
  final DateTime collectionTime;
  final DateTime queriedAt;
  final bool googleLocation;
  final int? batteryPercent;
  final int? accuracyMeters;

  factory TagLocation.fromMap(
    Map<Object?, Object?> source,
    DateTime queriedAt,
  ) {
    final map = source.map((key, value) => MapEntry('$key', value));
    return TagLocation(
      latitude: (map['latitude']! as num).toDouble(),
      longitude: (map['longitude']! as num).toDouble(),
      collectionTime: DateTime.fromMillisecondsSinceEpoch(
        map['collectionTimeMs']! as int,
        isUtc: true,
      ).toLocal(),
      queriedAt: queriedAt,
      googleLocation: map['googleLocation'] == true,
      batteryPercent: (map['batteryLevel'] as num?)?.toInt(),
      accuracyMeters: (map['accuracyLevel'] as num?)?.toInt(),
    );
  }
}

enum TagLocationPreset {
  latest('latest', 'Son konum'),
  last1Hour('last1Hour', 'Son 1 saat'),
  last2Hours('last2Hours', 'Son 2 saat'),
  last4Hours('last4Hours', 'Son 4 saat'),
  last6Hours('last6Hours', 'Son 6 saat');

  const TagLocationPreset(this.wireValue, this.label);
  final String wireValue;
  final String label;
}

class TagLocationQueryResult {
  const TagLocationQueryResult({
    required this.queriedAt,
    required this.locations,
  });

  final DateTime queriedAt;
  final List<TagLocation> locations;
  TagLocation? get latest => locations.isEmpty ? null : locations.last;
}

class FindDeviceOutcome {
  const FindDeviceOutcome({
    required this.triggered,
    this.rawBattery,
    this.serverBatteryLevel,
  });

  final bool triggered;
  final int? rawBattery;
  final int? serverBatteryLevel;

  factory FindDeviceOutcome.fromMap(Map<Object?, Object?> source) {
    final map = source.map((key, value) => MapEntry('$key', value));
    return FindDeviceOutcome(
      triggered: map['triggered'] == true,
      rawBattery: (map['rawBattery'] as num?)?.toInt(),
      serverBatteryLevel: (map['serverBatteryLevel'] as num?)?.toInt(),
    );
  }
}

class TagLogArchive {
  const TagLogArchive({
    required this.filePath,
    required this.fileSizeBytes,
    required this.createdAt,
  });

  final String filePath;
  final int fileSizeBytes;
  final DateTime createdAt;

  factory TagLogArchive.fromMap(Map<Object?, Object?> source) {
    final map = source.map((key, value) => MapEntry('$key', value));
    return TagLogArchive(
      filePath: map['filePath']! as String,
      fileSizeBytes: (map['fileSizeBytes']! as num).toInt(),
      createdAt: DateTime.fromMillisecondsSinceEpoch(
        (map['createdAtMs']! as num).toInt(),
        isUtc: true,
      ).toLocal(),
    );
  }
}

/// Selects by timestamp because LATEST can contain primary and secondary-key rows.
TagLocation? selectLatestLocation(
  Iterable<Map<Object?, Object?>> records,
  DateTime queriedAt,
) {
  Map<Object?, Object?>? latest;
  var latestTime = 0;
  for (final record in records) {
    final raw = record['collectionTimeMs'];
    final time = raw is int ? raw : (raw is num ? raw.toInt() : 0);
    final latitude = (record['latitude'] as num?)?.toDouble();
    final longitude = (record['longitude'] as num?)?.toDouble();
    final validCoordinate =
        latitude != null &&
        longitude != null &&
        latitude.isFinite &&
        longitude.isFinite &&
        latitude >= -90 &&
        latitude <= 90 &&
        longitude >= -180 &&
        longitude <= 180;
    if (time > latestTime && validCoordinate) {
      latest = record;
      latestTime = time;
    }
  }
  return latest == null ? null : TagLocation.fromMap(latest, queriedAt);
}

List<TagLocation> parseLocationHistory(
  Iterable<Map<Object?, Object?>> records,
  DateTime queriedAt,
) {
  final locations = <TagLocation>[];
  for (final record in records) {
    final rawTime = record['collectionTimeMs'];
    final time = rawTime is num ? rawTime.toInt() : 0;
    final latitude = (record['latitude'] as num?)?.toDouble();
    final longitude = (record['longitude'] as num?)?.toDouble();
    if (time <= 0 ||
        latitude == null ||
        longitude == null ||
        !latitude.isFinite ||
        !longitude.isFinite ||
        latitude < -90 ||
        latitude > 90 ||
        longitude < -180 ||
        longitude > 180) {
      continue;
    }
    locations.add(TagLocation.fromMap(record, queriedAt));
  }
  locations.sort((a, b) => a.collectionTime.compareTo(b.collectionTime));
  return List.unmodifiable(locations);
}

class FindTagService {
  FindTagService({MethodChannel? methods, EventChannel? events})
    : _methods = methods ?? const MethodChannel('findtag_bridge/methods'),
      _events = events ?? const EventChannel('findtag_bridge/events');

  final MethodChannel _methods;
  final EventChannel _events;

  Stream<FindTagEvent>? _eventStream;
  Stream<FindTagEvent> get events => _eventStream ??= _events
      .receiveBroadcastStream()
      .map((event) => FindTagEvent.fromMap(event as Map<Object?, Object?>));

  Future<T?> _invoke<T>(String method, [Object? arguments]) async {
    try {
      return await _methods.invokeMethod<T>(method, arguments);
    } on PlatformException catch (error) {
      throw FindTagException.fromPlatform(error);
    } on MissingPluginException {
      throw const FindTagException(
        'platformNotSupported',
        'FindTag bu platformda kullanılamıyor.',
      );
    }
  }

  Future<Map<String, Object?>> initialize() async => Map<String, Object?>.from(
    (await _invoke<Map<Object?, Object?>>('initialize'))!,
  );

  Future<void> startScan({Duration timeout = const Duration(seconds: 15)}) =>
      _invoke<void>('startScan', {'timeoutMs': timeout.inMilliseconds});
  Future<void> stopScan() => _invoke<void>('stopScan');
  Future<SavedTag> bindDevice(String scanId) async => SavedTag.fromMap(
    (await _invoke<Map<Object?, Object?>>('bindDevice', {'scanId': scanId}))!,
  );
  Future<TagLocation?> getLatestLocation(String savedTagId) async {
    return (await getLocationData(savedTagId, TagLocationPreset.latest)).latest;
  }

  Future<TagLocationQueryResult> getLocationData(
    String savedTagId,
    TagLocationPreset preset,
  ) async {
    final response = (await _invoke<Map<Object?, Object?>>('getLocationData', {
      'savedTagId': savedTagId,
      'preset': preset.wireValue,
    }))!;
    final queriedAt = DateTime.fromMillisecondsSinceEpoch(
      (response['queriedAtMs']! as num).toInt(),
    );
    final records = (response['records']! as List)
        .cast<Map<Object?, Object?>>();
    return TagLocationQueryResult(
      queriedAt: queriedAt,
      locations: parseLocationHistory(records, queriedAt),
    );
  }

  Future<FindDeviceOutcome> findDevice(String scanId) async =>
      FindDeviceOutcome.fromMap(
        (await _invoke<Map<Object?, Object?>>('findDevice', {
          'scanId': scanId,
        }))!,
      );

  Future<TagLogArchive> exportLogs() async => TagLogArchive.fromMap(
    (await _invoke<Map<Object?, Object?>>('exportLogs'))!,
  );
  Future<Map<String, Object?>> startBleDiagnosticScan({
    Duration timeout = const Duration(seconds: 15),
  }) async => Map<String, Object?>.from(
    (await _invoke<Map<Object?, Object?>>('startBleDiagnosticScan', {
      'timeoutMs': timeout.inMilliseconds,
    }))!,
  );
  Future<Map<String, Object?>> connectAndReadBleDiagnostics(
    String deviceId,
  ) async => Map<String, Object?>.from(
    (await _invoke<Map<Object?, Object?>>('connectAndReadBleDiagnostics', {
      'deviceId': deviceId,
    }))!,
  );
  Future<Map<String, Object?>> listenBleDiagnosticNotifications() async =>
      Map<String, Object?>.from(
        (await _invoke<Map<Object?, Object?>>(
          'listenBleDiagnosticNotifications',
        ))!,
      );
  Future<Map<String, Object?>> stopBleDiagnostics() async =>
      Map<String, Object?>.from(
        (await _invoke<Map<Object?, Object?>>('stopBleDiagnostics'))!,
      );
  Future<void> release() => _invoke<void>('release');
  Future<void> requestBluetoothPermissions() =>
      _invoke<void>('requestBluetoothPermissions');
  Future<void> openAppSettings() => _invoke<void>('openAppSettings');
  Future<void> openMap(double latitude, double longitude) =>
      _invoke<void>('openMap', {'latitude': latitude, 'longitude': longitude});

  Future<Map<String, Object?>> credentialStatus() async =>
      Map<String, Object?>.from(
        (await _invoke<Map<Object?, Object?>>('credentialStatus'))!,
      );
  Future<void> saveCredentials(String apiKey, String apiSecret) =>
      _invoke<void>('saveCredentials', {
        'apiKey': apiKey,
        'apiSecret': apiSecret,
      });
  Future<void> clearCredentials() => _invoke<void>('clearCredentials');
  Future<List<SavedTag>> savedTags() async =>
      ((await _invoke<List<Object?>>('savedTags')) ?? const [])
          .cast<Map<Object?, Object?>>()
          .map(SavedTag.fromMap)
          .toList(growable: false);
  Future<String> revealDeviceKey(String id) async =>
      (await _invoke<String>('revealDeviceKey', {'savedTagId': id}))!;
  Future<void> renameSavedTag(String id, String name) =>
      _invoke<void>('renameSavedTag', {'savedTagId': id, 'name': name});
  Future<void> removeSavedTag(String id) =>
      _invoke<void>('removeSavedTag', {'savedTagId': id});
}
