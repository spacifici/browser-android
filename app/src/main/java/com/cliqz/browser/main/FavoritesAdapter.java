package com.cliqz.browser.main;

import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.cliqz.browser.R;
import com.cliqz.browser.main.search.FreshtabGetLogoCallback;
import com.cliqz.browser.main.search.IconViewHolder;
import com.cliqz.jsengine.Engine;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

/**
 * @author Ravjit Uppal
 */
public class FavoritesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface ClickListener {
        void onClick(View view, int position);

        void onLongPress(View view, int position);
    }

    private ArrayList<FavoriteModel> favoriteList;
    private Engine engine;
    private Handler handler;
    private ClickListener clickListener;

    ArrayList<Integer> multiSelectList = new ArrayList<>();

    public FavoritesAdapter(ArrayList<FavoriteModel> favoriteList, Engine engine, Handler handler,
                            ClickListener clickListener) {
        this.favoriteList = favoriteList;
        this.engine = engine;
        this.handler = handler;
        this.clickListener = clickListener;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        final View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.favorite_viewholder,
                parent, false);
        return new FavoritesViewHolder(view);
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        String url;
        try {
            url = new URL(favoriteList.get(position).getUrl()).getHost();
        } catch (MalformedURLException e) {
            url = favoriteList.get(position).getUrl();
            e.printStackTrace();
        }
        final FavoritesViewHolder favoritesViewHolder = (FavoritesViewHolder) holder;
        favoritesViewHolder.url.setText(url);
        favoritesViewHolder.title.setText(favoriteList.get(position).getTitle());
        loadIcon(favoritesViewHolder, favoriteList.get(position).getUrl());
        if (multiSelectList.contains(position)) {
            favoritesViewHolder.selectedOverlay.setBackgroundColor(0x7700AEF0);
        } else {
            favoritesViewHolder.selectedOverlay.setBackgroundColor(0x00000000);
        }
    }

    @Override
    public int getItemCount() {
        return favoriteList.size();
    }


    private void loadIcon(final FavoritesViewHolder holder, String url) {
        engine.callAction("getLogoDetails", new FreshtabGetLogoCallback(holder, handler, false), url);
    }

    private class FavoritesViewHolder extends IconViewHolder {

        public TextView url;
        public TextView title;
        View selectedOverlay;
        View favorites_view_parent;

        FavoritesViewHolder(View view) {
            super(view);
            final Resources resources = view.getResources();
            url = view.findViewById(R.id.history_url);
            title = view.findViewById(R.id.history_title);
            selectedOverlay = view.findViewById(R.id.selectedOverLay);
            favorites_view_parent = view.findViewById(R.id.history_view_parent);
            favorites_view_parent.setId(R.id.favorites_view_parent);
            favorites_view_parent.setOnClickListener(v -> clickListener.onClick(v, getAdapterPosition()));
            favorites_view_parent.setOnLongClickListener(v -> {
                clickListener.onLongPress(v, getAdapterPosition());
                return true;
            });
        }
    }
}