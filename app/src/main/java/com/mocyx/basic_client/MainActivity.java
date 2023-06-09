package com.mocyx.basic_client;

import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.mocyx.basic_client.bio.BioTcpHandler;
import com.mocyx.basic_client.protocol.tcpip.Packet;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends AppCompatActivity {

    public ListView listView;
    public ArrayAdapter<String> adapter;

    private boolean autoScroll = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        listView = findViewById(R.id.packets);
        listView.setAdapter(adapter);

        EventBus.getDefault().register(this);

        setSupportActionBar(toolbar);

        startVpn();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(String event) {
        adapter.add(event);
        if (autoScroll)
            listView.smoothScrollToPosition(adapter.getCount() - 1);
    }

    private static final String TAG = BioTcpHandler.class.getSimpleName();

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_clear) {
            adapter.clear();
            return true;
        } else if (id == R.id.action_autoscroll) {
            autoScroll = !autoScroll;
            listView.smoothScrollToPosition(adapter.getCount() - 1);
        }

        return super.onOptionsItemSelected(item);
    }

    private static final int VPN_REQUEST_CODE = 0x0F;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            //waitingForVPNStart = true;
            startService(new Intent(this, LocalVPNService.class));
            //enableButton(false);
        }
    }

    private void startVpn() {

        Intent vpnIntent = VpnService.prepare(this);

        if (vpnIntent != null)
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        else
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    public static void displayStuff(String whichHost, InetAddress inetAddress) {
        System.out.println("--------------------------");
        System.out.println("Which Host:" + whichHost);
        System.out.println("Canonical Host Name:" + inetAddress.getCanonicalHostName());
        System.out.println("Host Name:" + inetAddress.getHostName());
        System.out.println("Host Address:" + inetAddress.getHostAddress());
    }
}
