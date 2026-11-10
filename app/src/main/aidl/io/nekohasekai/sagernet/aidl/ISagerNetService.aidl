package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  void stopForMaintenance();
  String getMaintenanceResult();
  void finishMaintenance();
  boolean restartStoppedProcess();
  boolean clearTraffic(long groupId);
}
