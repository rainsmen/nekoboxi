package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();
  String getPendingStopError();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  oneway void stopForMaintenance(String requestId);
  String getMaintenanceResult(String requestId);
  oneway void finishMaintenance(String requestId);
  oneway void restartStoppedProcess(String requestId, long deadlineElapsedRealtime);
  oneway void cancelRestart(String requestId);
  boolean clearTraffic(long groupId);
}
