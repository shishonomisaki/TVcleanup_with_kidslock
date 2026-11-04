package io.github.shishonomisaki.parentlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Android's answer to an update: show its install screen on the TV, or note why it failed. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);   // allowed from the background: this app may draw over others
                Updater.state = "confirm";
                return;
            }
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            android.util.Log.i("ParentLock", "update not installed: " + status + " "
                    + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            Updater.state = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "" : "update_fail";
        }
    }
}
