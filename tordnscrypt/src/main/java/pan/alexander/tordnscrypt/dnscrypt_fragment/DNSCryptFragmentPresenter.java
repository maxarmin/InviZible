package pan.alexander.tordnscrypt.dnscrypt_fragment;

/*
    This file is part of InviZible Pro.

    InviZible Pro is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    InviZible Pro is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with InviZible Pro.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019-2020 by Garmatin Oleksandr invizible.soft@gmail.com
*/

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.text.Html;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

import pan.alexander.tordnscrypt.MainActivity;
import pan.alexander.tordnscrypt.R;
import pan.alexander.tordnscrypt.dialogs.NotificationDialogFragment;
import pan.alexander.tordnscrypt.dialogs.NotificationHelper;
import pan.alexander.tordnscrypt.modules.ModulesAux;
import pan.alexander.tordnscrypt.modules.ModulesKiller;
import pan.alexander.tordnscrypt.modules.ModulesRunner;
import pan.alexander.tordnscrypt.modules.ModulesStatus;
import pan.alexander.tordnscrypt.settings.PathVars;
import pan.alexander.tordnscrypt.utils.OwnFileReader;
import pan.alexander.tordnscrypt.utils.PrefManager;
import pan.alexander.tordnscrypt.utils.enums.ModuleState;
import pan.alexander.tordnscrypt.vpn.ResourceRecord;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPN;
import pan.alexander.tordnscrypt.vpn.service.ServiceVPNHelper;

import static pan.alexander.tordnscrypt.TopFragment.appVersion;
import static pan.alexander.tordnscrypt.utils.RootExecService.LOG_TAG;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RESTARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.RUNNING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STARTING;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPED;
import static pan.alexander.tordnscrypt.utils.enums.ModuleState.STOPPING;
import static pan.alexander.tordnscrypt.utils.enums.OperationMode.VPN_MODE;

public class DNSCryptFragmentPresenter implements DNSCryptFragmentPresenterCallbacks {
    private boolean bound;

    private int displayLogPeriod = -1;

    private DNSCryptFragmentView view;
    private Timer timer = null;
    private volatile OwnFileReader logFile;
    private ModulesStatus modulesStatus;
    private ModuleState fixedModuleState;
    private ServiceConnection serviceConnection;
    private ServiceVPN serviceVPN;
    private ArrayList<ResourceRecord> savedResourceRecords;

    public DNSCryptFragmentPresenter(DNSCryptFragmentView view) {
        this.view = view;
    }

    public void onStart(Context context) {
        if (context == null || view == null) {
            return;
        }

        PathVars pathVars = PathVars.getInstance(context);
        String appDataDir = pathVars.getAppDataDir();

        modulesStatus = ModulesStatus.getInstance();

        savedResourceRecords = new ArrayList<>();

        logFile = new OwnFileReader(context, appDataDir + "/logs/DnsCrypt.log");

        if (isDNSCryptInstalled(context)) {
            setDNSCryptInstalled(true);

            if (modulesStatus.getDnsCryptState() == STOPPING) {
                setDnsCryptStopping();

                displayLog(1000);
            } else if (isSavedDNSStatusRunning(context) || modulesStatus.getDnsCryptState() == RUNNING) {
                setDnsCryptRunning();

                if (modulesStatus.getDnsCryptState() != RESTARTING) {
                    modulesStatus.setDnsCryptState(RUNNING);
                }

                displayLog(1000);

            } else {
                setDnsCryptStopped();
                modulesStatus.setDnsCryptState(STOPPED);
            }

        } else {
            setDNSCryptInstalled(false);
        }
    }

    public void onStop(Context context) {
        stopDisplayLog();
        unbindVPNService(context);
        view = null;
    }

    @Override
    public boolean isDNSCryptInstalled(Context context) {
        if (context == null) {
            return false;
        }

        return new PrefManager(context).getBoolPref("DNSCrypt Installed");
    }

    @Override
    public boolean isSavedDNSStatusRunning(Context context) {
        return new PrefManager(context).getBoolPref("DNSCrypt Running");
    }

    @Override
    public void saveDNSStatusRunning(Context context, boolean running) {
        new PrefManager(context).setBoolPref("DNSCrypt Running", running);
    }

    @Override
    public void displayLog(int period) {

        if (period == displayLogPeriod) {
            return;
        }

        displayLogPeriod = period;

        if (timer != null) {
            timer.purge();
            timer.cancel();
        }

        timer = new Timer();

        timer.schedule(new TimerTask() {
            int loop = 0;
            String previousLastLines = "";

            @Override
            public void run() {
                if (view == null || view.getFragmentActivity() == null || logFile == null) {
                    return;
                }

                final String lastLines = logFile.readLastLines();

                if (++loop > 120) {
                    loop = 0;
                    displayLog(10000);
                }

                final boolean displayed = displayDnsResponses(lastLines);

                if (view == null || view.getFragmentActivity() == null || logFile == null) {
                    return;
                }

                view.getFragmentActivity().runOnUiThread(() -> {

                    if (view == null || view.getFragmentActivity() == null || lastLines == null || lastLines.isEmpty()) {
                        return;
                    }

                    if (!previousLastLines.contentEquals(lastLines)) {

                        dnsCryptStartedSuccessfully(lastLines);

                        dnsCryptStartedWithError(view.getFragmentActivity(), lastLines);

                        if (!displayed) {
                            view.setDNSCryptLogViewText(Html.fromHtml(lastLines));
                        }

                        previousLastLines = lastLines;
                    }

                    refreshDNSCryptState(view.getFragmentActivity());

                });

            }
        }, 1000, period);

    }

    @Override
    public void stopDisplayLog() {
        if (timer != null) {
            timer.purge();
            timer.cancel();
            timer = null;

            displayLogPeriod = -1;
        }
    }

    private void setDnsCryptStarting() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStarting, R.color.textModuleStatusColorStarting);
    }

    @Override
    public void setDnsCryptRunning() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSRunning, R.color.textModuleStatusColorRunning);
        view.setStartButtonText(R.string.btnDNSCryptStop);
    }

    private void setDnsCryptStopping() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStopping, R.color.textModuleStatusColorStopping);
    }

    @Override
    public void setDnsCryptStopped() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSStop, R.color.textModuleStatusColorStopped);
        view.setStartButtonText(R.string.btnDNSCryptStart);
        view.setDNSCryptLogViewText();
    }

    @Override
    public void setDnsCryptInstalling() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSInstalling, R.color.textModuleStatusColorInstalling);
    }

    @Override
    public void setDnsCryptInstalled() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.tvDNSInstalled, R.color.textModuleStatusColorInstalled);
    }

    @Override
    public void setDNSCryptStartButtonEnabled(boolean enabled) {
        if (view == null) {
            return;
        }

        view.setDNSCryptStartButtonEnabled(enabled);
    }

    @Override
    public void setDNSCryptProgressBarIndeterminate(boolean indeterminate) {
        if (view == null) {
            return;
        }

        view.setDNSCryptProgressBarIndeterminate(indeterminate);
    }

    private void setDNSCryptInstalled(boolean installed) {
        if (view == null) {
            return;
        }

        if (installed) {
            view.setDNSCryptStartButtonEnabled(true);
        } else {
            view.setDNSCryptStatus(R.string.tvDNSNotInstalled, R.color.textModuleStatusColorAlert);
        }
    }

    @Override
    public void setDnsCryptSomethingWrong() {
        if (view == null) {
            return;
        }

        view.setDNSCryptStatus(R.string.wrong, R.color.textModuleStatusColorAlert);
    }

    private void dnsCryptStartedSuccessfully(String lines) {

        if (view == null || modulesStatus == null) {
            return;
        }

        if ((modulesStatus.getDnsCryptState() == STARTING
                || modulesStatus.getDnsCryptState() == RUNNING)
                && lines.contains("lowest initial latency")) {

            if (!modulesStatus.isUseModulesWithRoot()) {
                view.setDNSCryptProgressBarIndeterminate(false);
            }

            setDnsCryptRunning();
        }
    }

    private void dnsCryptStartedWithError(Context context, String lastLines) {

        if (context == null || view == null) {
            return;
        }

        FragmentManager fragmentManager = view.getFragmentFragmentManager();

        if ((lastLines.contains("connect: connection refused")
                || lastLines.contains("ERROR"))
                && !lastLines.contains(" OK ")) {
            Log.e(LOG_TAG, "DNSCrypt Error: " + lastLines);

            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_no_internet).toString(), "helper_dnscrypt_no_internet");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }

        } else if (lastLines.contains("[CRITICAL]") && lastLines.contains("[FATAL]")) {

            if (fragmentManager != null) {
                NotificationHelper notificationHelper = NotificationHelper.setHelperMessage(
                        context, context.getText(R.string.helper_dnscrypt_no_internet).toString(), "helper_dnscrypt_no_internet");
                if (notificationHelper != null) {
                    notificationHelper.show(fragmentManager, NotificationHelper.TAG_HELPER);
                }
            }

            Log.e(LOG_TAG, "DNSCrypt FATAL Error: " + lastLines);

            stopDNSCrypt(context);
        }
    }

    private boolean displayDnsResponses(String savedLines) {

        if (view == null || modulesStatus == null) {
            return false;
        }

        if (modulesStatus.getMode() != VPN_MODE) {
            if (!savedResourceRecords.isEmpty() && view != null && view.getFragmentActivity() != null) {
                savedResourceRecords.clear();
                view.getFragmentActivity().runOnUiThread(() -> {
                    if (view != null && view.getFragmentActivity() != null  && logFile != null) {
                        view.setDNSCryptLogViewText(Html.fromHtml(logFile.readLastLines()));
                    }
                });
                return true;
            } else {
                return false;
            }

        } else if (view != null && view.getFragmentActivity() != null && modulesStatus.getMode() == VPN_MODE && !bound) {
            bindToVPNService(view.getFragmentActivity());
            return false;
        }

        if (modulesStatus.getDnsCryptState() == RESTARTING) {
            clearResourceRecords();
            savedResourceRecords.clear();
            return false;
        }

        ArrayList<ResourceRecord> resourceRecords = new ArrayList<>(getResourceRecords());

        if (resourceRecords.equals(savedResourceRecords) || resourceRecords.isEmpty()) {
            return false;
        }

        savedResourceRecords = resourceRecords;

        ResourceRecord rr;
        StringBuilder lines = new StringBuilder();

        lines.append(savedLines);

        lines.append("<br />");

        for (int i = 0; i < savedResourceRecords.size(); i++) {
            rr = savedResourceRecords.get(i);

            if (appVersion.startsWith("g") && rr.HInfo.contains("block_ipv6")) {
                continue;
            }

            if (rr.Resource.equals("0.0.0.0") || rr.Resource.equals("127.0.0.1") || rr.HInfo.contains("dnscrypt") || rr.Rcode != 0) {
                if (!rr.AName.isEmpty()) {
                    lines.append("<font color=#f08080>").append(rr.AName);

                    if (rr.HInfo.contains("block_ipv6")) {
                        lines.append(" ipv6");
                    }

                    lines.append("</font>");
                } else {
                    lines.append("<font color=#f08080>").append(rr.QName).append("</font>");
                }
            } else {
                lines.append("<font color=#0f7f7f>").append(rr.AName).append("</font>");
            }

            if (i < savedResourceRecords.size() - 1) {
                lines.append("<br />");
            }
        }

        if (view != null && view.getFragmentActivity() != null) {
            view.getFragmentActivity().runOnUiThread(() -> {
                if (view != null && view.getFragmentActivity() != null) {
                    view.setDNSCryptLogViewText(Html.fromHtml(lines.toString()));
                } else {
                    savedResourceRecords.clear();
                }
            });
        }

        return true;
    }

    private LinkedList<ResourceRecord> getResourceRecords() {
        if (serviceVPN != null) {
            return serviceVPN.getResourceRecords();
        }
        return new LinkedList<>();
    }

    private void clearResourceRecords() {
        if (serviceVPN != null) {
            serviceVPN.clearResourceRecords();
        }
    }

    @Override
    public void refreshDNSCryptState(Context context) {

        if (context == null || modulesStatus == null || view == null) {
            return;
        }

        ModuleState currentModuleState = modulesStatus.getDnsCryptState();

        if (currentModuleState.equals(fixedModuleState) && currentModuleState != STOPPED) {
            return;
        }

        if (currentModuleState == STARTING) {

            displayLog(1000);

        } else if (currentModuleState == RUNNING && view.getFragmentActivity() != null) {

            ServiceVPNHelper.prepareVPNServiceIfRequired(view.getFragmentActivity(), modulesStatus);

            view.setDNSCryptStartButtonEnabled(true);

            saveDNSStatusRunning(context, true);

            view.setStartButtonText(R.string.btnDNSCryptStop);

            displayLog(5000);

            if (modulesStatus.getMode() == VPN_MODE && !bound) {
                bindToVPNService(context);
            }

        } else if (currentModuleState == STOPPED) {

            stopDisplayLog();

            if (isSavedDNSStatusRunning(context)) {
                setDNSCryptStoppedBySystem(context);
            } else {
                setDnsCryptStopped();
            }

            view.setDNSCryptProgressBarIndeterminate(false);

            saveDNSStatusRunning(context, false);

            view.setDNSCryptStartButtonEnabled(true);
        }

        fixedModuleState = currentModuleState;
    }

    private void setDNSCryptStoppedBySystem(Context context) {
        if (view == null) {
            return;
        }

        setDnsCryptStopped();

        FragmentManager fragmentManager = view.getFragmentFragmentManager();

        if (context != null && modulesStatus != null) {

            modulesStatus.setDnsCryptState(STOPPED);

            ModulesAux.requestModulesStatusUpdate(context);

            if (fragmentManager != null) {
                DialogFragment notification = NotificationDialogFragment.newInstance(R.string.helper_dnscrypt_stopped);
                notification.show(fragmentManager, "NotificationDialogFragment");
            }

            Log.e(LOG_TAG, context.getText(R.string.helper_dnscrypt_stopped).toString());
        }

    }

    private void runDNSCrypt(Context context) {
        if (context == null) {
            return;
        }

        ModulesRunner.runDNSCrypt(context);
    }

    private void stopDNSCrypt(Context context) {
        if (context == null) {
            return;
        }

        ModulesKiller.stopDNSCrypt(context);
    }

    private void bindToVPNService(Context context) {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                serviceVPN = ((ServiceVPN.VPNBinder) service).getService();
                bound = true;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                bound = false;
            }
        };

        if (context != null) {
            Intent intent = new Intent(context, ServiceVPN.class);
            context.bindService(intent, serviceConnection, 0);
        }
    }

    private void unbindVPNService(Context context) {
        if (bound && serviceConnection != null && context != null) {
            context.unbindService(serviceConnection);
            bound = false;
        }
    }

    public void startButtonOnClick(Context context) {
        if (context == null || view == null || modulesStatus == null) {
            return;
        }

        if (((MainActivity) context).childLockActive) {
            Toast.makeText(context, context.getText(R.string.action_mode_dialog_locked), Toast.LENGTH_LONG).show();
            return;
        }


        view.setDNSCryptStartButtonEnabled(false);

        //cleanLogFileNoRootMethod(context);


        if (new PrefManager(context).getBoolPref("Tor Running")
                && !new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            if (modulesStatus.isContextUIDUpdateRequested()) {
                Toast.makeText(context, R.string.please_wait, Toast.LENGTH_SHORT).show();
                view.setDNSCryptStartButtonEnabled(true);
                return;
            }

            setDnsCryptStarting();

            runDNSCrypt(context);

            displayLog(1000);
        } else if (!new PrefManager(context).getBoolPref("Tor Running")
                && !new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            if (modulesStatus.isContextUIDUpdateRequested()) {
                Toast.makeText(context, R.string.please_wait, Toast.LENGTH_SHORT).show();
                view.setDNSCryptStartButtonEnabled(true);
                return;
            }

            setDnsCryptStarting();

            runDNSCrypt(context);

            displayLog(1000);
        } else if (!new PrefManager(context).getBoolPref("Tor Running")
                && new PrefManager(context).getBoolPref("DNSCrypt Running")) {
            setDnsCryptStopping();
            stopDNSCrypt(context);
        } else if (new PrefManager(context).getBoolPref("Tor Running")
                && new PrefManager(context).getBoolPref("DNSCrypt Running")) {

            setDnsCryptStopping();
            stopDNSCrypt(context);
        }

        view.setDNSCryptProgressBarIndeterminate(true);
    }

}
