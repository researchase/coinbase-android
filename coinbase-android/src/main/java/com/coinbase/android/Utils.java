package com.coinbase.android;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentManager;
import android.text.Html;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.FrameLayout;

import com.coinbase.api.LoginManager;
import com.coinbase.api.entity.AccountChange;
import com.coinbase.api.entity.Contact;
import com.google.inject.Inject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import org.joda.money.BigMoney;
import org.joda.money.BigMoneyProvider;
import org.joda.money.format.MoneyAmountStyle;
import org.joda.money.format.MoneyFormatter;
import org.joda.money.format.MoneyFormatterBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

import roboguice.RoboGuice;

import static java.lang.Math.min;

public class Utils {

  private Utils() { }

  public static final void showMessageDialog(FragmentManager m, String message) {

    MessageDialogFragment fragment = new MessageDialogFragment();
    Bundle args = new Bundle();
    args.putString(MessageDialogFragment.ARG_MESSAGE, message);
    fragment.setArguments(args);

    try {
      fragment.show(m, "Utils.showMessageDialog");
    } catch(IllegalStateException e) {
      // Expected if application has been destroyed
      // Ignore
    }
  }

  public static enum CurrencyType {
    BTC(8, 2),
    BTC_FUZZY(4, 2),
    TRADITIONAL(2, 2);


    int maximumFractionDigits;
    int minimumFractionDigits;

    CurrencyType(int max, int min) {
      maximumFractionDigits = max;
      minimumFractionDigits = min;
    }
  }

  public static class ContactsAutoCompleteAdapter extends ArrayAdapter<String> implements Filterable {
    private ArrayList<String> resultList;

    @Inject
    private LoginManager mLoginManager;

    public ContactsAutoCompleteAdapter(Context context, int textViewResourceId) {
      super(context, textViewResourceId);
      RoboGuice.getInjector(context).injectMembers(this);
    }

    @Override
    public int getCount() {
      return resultList.size();
    }

    @Override
    public String getItem(int index) {
      return resultList.get(index);
    }

    @Override
    public Filter getFilter() {
      return new Filter() {
        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
          FilterResults filterResults = new FilterResults();
          if (constraint != null) {
            // Retrieve the autocomplete results.
            resultList = fetchContacts(constraint.toString());

            // Assign the data to the FilterResults
            filterResults.values = resultList;
            filterResults.count = resultList.size();
          }
          return filterResults;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
          if (results != null && results.count > 0) {
            notifyDataSetChanged();
          }
          else {
            notifyDataSetInvalidated();
          }
        }
      };
    }

    private ArrayList<String> fetchContacts(String filter) {
      ArrayList<String> result = new ArrayList<String>();

      try {
        List<Contact> contacts =
                mLoginManager.getClient().getContacts(filter).getContacts();
        for (Contact contact : contacts) {
          result.add(contact.getEmail());
        }
      } catch (Exception e) {
        e.printStackTrace();
      }

      return result;
    }
  }

  // http://stackoverflow.com/a/19494006/764272 (modified)
  public static class AndroidBug5497Workaround {

    private View mChildOfContent;
    private int usableHeightPrevious;
    private FrameLayout.LayoutParams frameLayoutParams;
    private ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener;

    public void startAssistingActivity(Activity activity) {
      FrameLayout content = (FrameLayout) activity.findViewById(android.R.id.content);
      mChildOfContent = content.getChildAt(0);
      globalLayoutListener = new ViewTreeObserver.OnGlobalLayoutListener() {
        public void onGlobalLayout() {
          possiblyResizeChildOfContent();
        }
      };
      mChildOfContent.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
      frameLayoutParams = (FrameLayout.LayoutParams) mChildOfContent.getLayoutParams();
    }

    public void stopAssistingActivity() {
      if (globalLayoutListener != null) {
        mChildOfContent.getViewTreeObserver().removeGlobalOnLayoutListener(globalLayoutListener);
        frameLayoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        mChildOfContent.requestLayout();
      }
    }

    private void possiblyResizeChildOfContent() {
      int usableHeightNow = computeUsableHeight();
      if (usableHeightNow != usableHeightPrevious) {
        int usableHeightSansKeyboard = mChildOfContent.getRootView().getHeight();
        int heightDifference = usableHeightSansKeyboard - usableHeightNow;
        if (heightDifference > (usableHeightSansKeyboard/4)) {
          // keyboard probably just became visible
          frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;
        } else {
          // keyboard probably just became hidden
          frameLayoutParams.height = usableHeightSansKeyboard;
        }
        mChildOfContent.requestLayout();
        usableHeightPrevious = usableHeightNow;
      }
    }

    private int computeUsableHeight() {
      Rect r = new Rect();
      mChildOfContent.getWindowVisibleDisplayFrame(r);
      return (r.bottom - r.top);
    }
  }

  public static final String formatMoney(BigMoneyProvider money) {
    MoneyFormatter formatter;

    if (money.toBigMoney().getCurrencyUnit().getCurrencyCode().equalsIgnoreCase("BTC")) {
      // Need to specify bitcoin symbol
      formatter = new MoneyFormatterBuilder()
              .appendLiteral("\u0E3F")
              .appendAmount(MoneyAmountStyle.LOCALIZED_NO_GROUPING)
              .toFormatter();

      // Strip trailing zeros past four decimal places
      BigDecimal displayAmount = money.toBigMoney().getAmount().stripTrailingZeros();
      if (displayAmount.scale() < 4) {
        displayAmount = displayAmount.setScale(4);
      }
      money = BigMoney.of (
              money.toBigMoney().getCurrencyUnit(),
              displayAmount
      );
    } else {
      // Build money formatter from default locale
      formatter = new MoneyFormatterBuilder()
              .appendCurrencySymbolLocalized()
              .appendAmountLocalized()
              .toFormatter();
    }

    String result = formatter.print(money);
    return result;
  }

  public static final String formatMoneyRounded(BigMoneyProvider money, int scale) {
    BigMoney rounded = money.toBigMoney().withScale(scale, RoundingMode.HALF_EVEN);

    return formatMoney(rounded);
  }

  public static final String formatMoneyRounded(BigMoneyProvider money) {
    return formatMoneyRounded(money, min(4, money.toBigMoney().getCurrencyUnit().getDecimalPlaces()));
  }

  public static final String formatCurrencyAmount(String amount) {
    return formatCurrencyAmount(new BigDecimal(amount), false, CurrencyType.BTC);
  }

  public static final String formatCurrencyAmount(BigDecimal amount) {
    return formatCurrencyAmount(amount, false, CurrencyType.BTC);
  }

  public static final String formatCurrencyAmount(String amount, boolean ignoreSign) {
    return formatCurrencyAmount(new BigDecimal(amount), ignoreSign, CurrencyType.BTC);
  }

  public static final String formatCurrencyAmount(String amount, boolean ignoreSign, CurrencyType type) {
    return formatCurrencyAmount(new BigDecimal(amount), ignoreSign, type);
  }

  public static final String formatCurrencyAmount(BigDecimal balanceNumber, boolean ignoreSign, CurrencyType type) {

    Locale locale = Locale.getDefault();
    NumberFormat nf = NumberFormat.getInstance(locale);
    nf.setMaximumFractionDigits(type.maximumFractionDigits);
    nf.setMinimumFractionDigits(type.minimumFractionDigits);

    if(ignoreSign && balanceNumber.compareTo(BigDecimal.ZERO) == -1) {
      balanceNumber = balanceNumber.multiply(new BigDecimal(-1));
    }

    return nf.format(balanceNumber);
  }

  /** Based off of ZXing Android client code */
  public static Bitmap createBarcode(String contents, BarcodeFormat format,
                                     int desiredWidth, int desiredHeight) throws WriterException {

    Hashtable<EncodeHintType,Object> hints = new Hashtable<EncodeHintType,Object>(2);
    MultiFormatWriter writer = new MultiFormatWriter();
    BitMatrix result = writer.encode(contents, format, desiredWidth, desiredHeight, hints);

    int width = result.getWidth();
    int height = result.getHeight();
    int fgColor = 0xFF000000;
    int bgColor = 0x00FFFFFF;
    int[] pixels = new int[width * height];

    for (int y = 0; y < height; y++) {
      int offset = y * width;
      for (int x = 0; x < width; x++) {
        pixels[offset + x] = result.get(x, y) ? fgColor : bgColor;
      }
    }

    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
    return bitmap;
  }

  public static ContactsAutoCompleteAdapter getEmailAutocompleteAdapter(final Context context) {
    return new ContactsAutoCompleteAdapter(context, android.R.layout.simple_spinner_dropdown_item);
  }

  public static CharSequence generateAccountChangeSummary(Context c, AccountChange change) {

    AccountChange.Cache cache = change.getCache();

    AccountChange.Cache.Category category = cache.getCategory();
    String otherName = cache.getOtherUser().getName();
    boolean senderMe = change.getAmount().isNegative();

    if (otherName.contains("external account")) {
        otherName = c.getString(R.string.transaction_user_external);
    } else {
        otherName.replace(" ", "\u00A0");
    }

    String html = null;

    switch (category) {
      case INVOICE:
        if(senderMe) {
          html = String.format(c.getString(R.string.transaction_summary_invoice_them), otherName);
        } else {
          html = String.format(c.getString(R.string.transaction_summary_invoice_me), otherName);
        }
        break;
      case REQUEST:
        if(senderMe) {
          html = String.format(c.getString(R.string.transaction_summary_request_me), otherName);
        } else {
          html = String.format(c.getString(R.string.transaction_summary_request_them), otherName);
        }
        break;
      case TRANSFER:
        if(senderMe) {
          html = c.getString(R.string.transaction_summary_sell);
        } else {
          html = c.getString(R.string.transaction_summary_buy);
        }
        break;
      default:
        if(senderMe) {
          html = String.format(c.getString(R.string.transaction_summary_send_me), otherName);
        } else {
          html = String.format(c.getString(R.string.transaction_summary_send_them), otherName);
        }
        break;
    }

    return Html.fromHtml(html);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public static <T> void runAsyncTaskConcurrently(AsyncTask<T, ?, ?> task, T... params) {

    if (PlatformUtils.hasHoneycomb()) {
      task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, params);
    } else {
      task.execute(params);
    }
  }

  public static String md5(String original) {
    MessageDigest md;

    try {
      md = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("MD5 does not exist", e);
    }

    md.update(original.getBytes());
    byte[] digest = md.digest();
    StringBuffer sb = new StringBuffer();
    for (byte b : digest) {
      int unsigned = b & 0xff;
      if (unsigned < 0x10)
        sb.append("0");
      sb.append(Integer.toHexString((unsigned)));
    }
    return sb.toString();
  }

  public static int getActiveAccount(Context c) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return activeAccount;
  }

  public static String getPrefsString(Context c, String key, String def) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return prefs.getString(String.format(key, activeAccount), def);
  }

  public static boolean inKioskMode(Context c) {
    return PreferenceManager.getDefaultSharedPreferences(c).getBoolean(Constants.KEY_KIOSK_MODE, false);
  }

  public static boolean getPrefsBool(Context c, String key, boolean def) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return prefs.getBoolean(String.format(key, activeAccount), def);
  }

  public static boolean putPrefsString(Context c, String key, String newValue) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return prefs.edit().putString(String.format(key, activeAccount), newValue).commit();
  }

  public static int getPrefsInt(Context c, String key, int def) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return prefs.getInt(String.format(key, activeAccount), def);
  }

  public static boolean putPrefsInt(Context c, String key, int newValue) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return prefs.edit().putInt(String.format(key, activeAccount), newValue).commit();
  }

  public static boolean incrementPrefsInt(Context c, String key) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    int current = prefs.getInt(String.format(key, activeAccount), 0);
    return prefs.edit().putInt(String.format(key, activeAccount), current + 1).commit();
  }

  public static boolean togglePrefsBool(Context c, String key, boolean def) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    boolean current = prefs.getBoolean(String.format(key, activeAccount), def);
    prefs.edit().putBoolean(String.format(key, activeAccount), !current).commit();
    return !current;
  }

  public static boolean putPrefsBool(Context c, String key, boolean newValue) {

    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
    int activeAccount = prefs.getInt(Constants.KEY_ACTIVE_ACCOUNT, -1);
    return prefs.edit().putBoolean(String.format(key, activeAccount), newValue).commit();
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public static void setClipboard(Context c, String text) {

    if (PlatformUtils.hasHoneycomb()) {

      android.content.ClipboardManager clipboard =
              (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
      ClipData clip = ClipData.newPlainText("Coinbase", text);
      clipboard.setPrimaryClip(clip);
    } else {

      android.text.ClipboardManager clipboard =
              (android.text.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
      clipboard.setText(text);
    }
  }

  public static boolean isConnectedOrConnecting(Context c) {
    ConnectivityManager cm =
            (ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
    boolean isConnected = activeNetwork != null &&
            activeNetwork.isConnectedOrConnecting();
    return isConnected;
  }

  public static JSONObject createAccountChangeForTransaction(Context c, JSONObject transaction, String category) throws JSONException {
    JSONObject accountChange = new JSONObject();
    accountChange.put("transaction_id", transaction.optString("id"));
    accountChange.put("created_at", transaction.getString("created_at"));
    accountChange.put("confirmed", !transaction.getString("status").equals("pending"));
    accountChange.put("amount", transaction.getJSONObject("amount"));
    JSONObject cache = new JSONObject();
    cache.put("category", category);
    boolean thisUserSender = Utils.getPrefsString(c, Constants.KEY_ACCOUNT_ID, "").equals(transaction.getJSONObject("sender").getString("id"));
    JSONObject otherUser = transaction.optJSONObject(thisUserSender ? "recipient" : "sender");
    if (otherUser == null) {
      otherUser = new JSONObject();
      otherUser.put("id", null);
      otherUser.put("name", "an external account");
    }
    cache.put("other_user", otherUser);
    accountChange.put("cache", cache);
    accountChange.put("delayed_transaction", transaction.optJSONObject("delayed_transaction"));
    return accountChange;
  }

  public static String convertStreamToString(java.io.InputStream is) {
    java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
    return s.hasNext() ? s.next() : "";
  }
}
