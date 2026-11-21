package io.github.regloom.dsphotword;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick-settings tile that turns "OK Google" listening on and off. */
public class ToggleTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean on = !TriggerPrefs.isEnabled(this);
        TriggerPrefs.setEnabled(this, on);
        if (on) {
            DspListener.get(this).start();
        } else {
            DspListener.get(this).stop();
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = TriggerPrefs.isEnabled(this);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel("OK Google");
        Icon icon = iconBitmap();
        if (icon != null) {
            tile.setIcon(icon);
        }
        tile.updateTile();
    }

    /**
     * The tile icon as a bitmap, rendered here: SystemUI can't load resources from this APK when
     * KernelSU hides module files from it, but it can draw a bitmap passed with the tile.
     */
    private Icon iconBitmap() {
        try {
            android.graphics.drawable.Drawable d = getDrawable(R.drawable.ic_hotword);
            if (d == null) return null;
            int size = (int) (24 * getResources().getDisplayMetrics().density);
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setTint(0xFFFFFFFF); // SystemUI tints tile icons itself by state
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            return Icon.createWithBitmap(bmp);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
