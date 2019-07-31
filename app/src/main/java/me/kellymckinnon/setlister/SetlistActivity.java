package me.kellymckinnon.setlister;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.ShareActionProvider;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuItemCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.spotify.sdk.android.authentication.AuthenticationClient;
import com.spotify.sdk.android.authentication.AuthenticationResponse;

import me.kellymckinnon.setlister.models.Show;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;

import me.kellymckinnon.setlister.fragments.SetlistFragment;
import me.kellymckinnon.setlister.utils.JSONRetriever;
import me.kellymckinnon.setlister.utils.Utility;

/**
 * Final activity which uses a SetlistFragment to display the setlist for the selected show, and
 * gives the option to create a Spotify playlist out of this setlist.
 */
public class SetlistActivity extends AppCompatActivity {

  private String mAccessToken;
  private ArrayList<String> mFailedSpotifySongs = new ArrayList<>();
  private ShareActionProvider mShareActionProvider;
  private Show mShow;

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_setlister, menu);
    MenuItem item = menu.findItem(R.id.action_share);
    item.setVisible(true);
    mShareActionProvider =
        (ShareActionProvider) MenuItemCompat.getActionProvider(item);
    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    if (mShow != null) {
      updateShareIntent();
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    int id = item.getItemId();

    if (id == R.id.action_about) {
      Utility.showAboutDialog(this);
      return true;
    } else if (id == R.id.action_feedback) {
      Utility.startFeedbackEmail(this);
      return true;
    }

    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Bundle arguments = getIntent().getExtras();
    mShow = arguments.getParcelable(SetlisterConstants.EXTRA_SHOW);

    if (mShareActionProvider != null) {
      updateShareIntent();
    }

    setContentView(R.layout.activity_setlist);

    SetlistFragment sf = new SetlistFragment();
    sf.setArguments(arguments);
    getSupportFragmentManager().beginTransaction().add(R.id.activity_setlist, sf).commit();
  }

  /** Provide information for share button */
  private void updateShareIntent() {
    String shareTitle = getString(R.string.setlist_share_title, mShow.getBand(), mShow.getDate(), mShow.getVenue());

    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType("text/plain");
    intent.putExtra(Intent.EXTRA_SUBJECT, shareTitle);
    StringBuilder text = new StringBuilder();
    text.append(shareTitle)
        .append(":\n");
    for (String s : mShow.getSongs()) {
      text.append("\n");
      text.append(s);
    }
    intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
    intent.putExtra(Intent.EXTRA_TEXT, text.toString());
    mShareActionProvider.setShareIntent(intent);
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);

    if (requestCode != SetlisterConstants.SPOTIFY_LOGIN_ACTIVITY_ID) {
      return; // This shouldn't happen
    }

    AuthenticationResponse response = AuthenticationClient.getResponse(resultCode, data);

    switch (response.getType()) {
      // Response was successful and contains auth token, we can create a Spotify playlist
      case TOKEN:
        mAccessToken = response.getAccessToken();
        Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.spotify_creating_playlist_snackbar),
            Snackbar.LENGTH_SHORT)
            .show();

        mFailedSpotifySongs = new ArrayList<>();
        new PlaylistCreator().execute();
        break;

      // Auth flow returned an error
      case ERROR:
        Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.spotify_connection_failed_snackbar),
            Snackbar.LENGTH_SHORT)
            .show();
      // Other cases mean that most likely auth flow was cancelled. We'll do nothing
    }
  }

  /**
   * Uses the Spotify API to create a playlist and add the songs from the setlist to the playlist
   */
  private class PlaylistCreator extends AsyncTask<Void, Void, Void> {

    @Override
    protected Void doInBackground(Void... params) {
      try {
        // Get username, which we need to create a playlist
        JSONObject userJson =
            JSONRetriever.getRequest("https://api.spotify.com/v1/me", "Bearer", mAccessToken);
        String username = userJson.getString("id");

        // Create an empty playlist for the authenticated user
        String createPlaylistUrl = "https://api.spotify.com/v1/users/" + username + "/playlists";
        JSONObject playlistInfo = new JSONObject();
        playlistInfo.put("name", mShow.getBand() + ", " + mShow.getVenue() + ", " + mShow.getDate());
        playlistInfo.put("public", "true");
        JSONObject createPlaylistJson =
            JSONRetriever.postRequest(createPlaylistUrl, "Bearer", mAccessToken, playlistInfo);

        // Get the newly created playlist so the fun can begin
        String playlistId = createPlaylistJson.getString("id");
        StringBuilder tracks = new StringBuilder();
        int numSongsAdded = 0;

        // Add songs one at a time
        for (String s : mShow.getSongs()) {
          // Only 100 songs can be added through the API
          if (numSongsAdded > 100) {
            mFailedSpotifySongs.add(s);
          }

          String songQuery = s.replace(' ', '+');
          String artistQuery = mShow.getBand().replace(' ', '+');
          try {
            JSONObject trackJson =
                JSONRetriever.getRequest(
                    "https://api.spotify.com/v1/search?q=track:"
                        + songQuery
                        + "%20artist:"
                        + artistQuery
                        + "&type=track&limit=5",
                    "Bearer",
                        mAccessToken);
            JSONObject tracking = trackJson.getJSONObject("tracks");
            JSONArray items = tracking.getJSONArray("items");
            JSONObject firstChoice = (JSONObject) items.get(0);

            // The first match isn't always the best one (e.g. X remix), so we check if
            // any of the top 5 are an exact match to X
            for (int i = 0; i < items.length(); i++) {
              JSONObject currentTrack = (JSONObject) items.get(i);
              if (currentTrack.getString("name").equals(s)) {
                firstChoice = currentTrack;
                break;
              }
            }

            tracks.append(firstChoice.getString("uri"));

            tracks.append(",");
            numSongsAdded++;
          } catch (JSONException e) {
            mFailedSpotifySongs.add(s);
          } catch (IOException e) {
            mFailedSpotifySongs.add(s);
          }
        }

        tracks.deleteCharAt(tracks.length() - 1); // Delete last comma

        String addSongsUrl =
            createPlaylistUrl + "/" + playlistId + "/tracks?uris=" + tracks.toString();
        JSONRetriever.postRequest(addSongsUrl, "Bearer", mAccessToken, null);
      } catch (JSONException e) {
        e.printStackTrace();
      } catch (IOException e) {
        e.printStackTrace();
      }

      return null;
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onPostExecute(Void aVoid) {
      Snackbar snackbar =
          Snackbar.make(
              findViewById(android.R.id.content),
              getString(R.string.spotify_playlist_created_snackbar),
              Snackbar.LENGTH_SHORT);

      // If there were missed songs, give the user the option to see what they were
      if (!mFailedSpotifySongs.isEmpty()) {
        snackbar
            .setDuration(Snackbar.LENGTH_LONG)
            .setAction(
                getResources()
                    .getQuantityString(
                        R.plurals.spotify_missing_songs_snackbar,
                        mFailedSpotifySongs.size(),
                        mFailedSpotifySongs.size()),
                new View.OnClickListener() {
                  @Override
                  public void onClick(View view) {
                    StringBuilder content = new StringBuilder();
                    content.append(getString(R.string.spotify_missing_songs_dialog_body));
                    content.append("\n");
                    for (String s : mFailedSpotifySongs) {
                      content.append("\n");
                      content.append("• ");
                      content.append(s);
                    }

                    new MaterialAlertDialogBuilder(SetlistActivity.this)
                            .setTitle(R.string.spotify_missing_songs_dialog_title)
                            .setMessage(content)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                  }
                })
            .setActionTextColor(ContextCompat.getColor(SetlistActivity.this, R.color.colorAccent));
      }
      snackbar.show();
      super.onPostExecute(aVoid);
    }
  }
}
