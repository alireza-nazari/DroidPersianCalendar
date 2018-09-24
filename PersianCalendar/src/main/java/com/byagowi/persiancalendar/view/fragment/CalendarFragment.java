package com.byagowi.persiancalendar.view.fragment;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.byagowi.persiancalendar.Constants;
import com.byagowi.persiancalendar.R;
import com.byagowi.persiancalendar.adapter.CalendarAdapter;
import com.byagowi.persiancalendar.adapter.CardTabsAdapter;
import com.byagowi.persiancalendar.databinding.EventsTabContentBinding;
import com.byagowi.persiancalendar.databinding.FragmentCalendarBinding;
import com.byagowi.persiancalendar.databinding.OwghatTabContentBinding;
import com.byagowi.persiancalendar.di.dependencies.AppDependency;
import com.byagowi.persiancalendar.di.dependencies.MainActivityDependency;
import com.byagowi.persiancalendar.entity.AbstractEvent;
import com.byagowi.persiancalendar.entity.DeviceCalendarEvent;
import com.byagowi.persiancalendar.entity.GregorianCalendarEvent;
import com.byagowi.persiancalendar.entity.IslamicCalendarEvent;
import com.byagowi.persiancalendar.entity.PersianCalendarEvent;
import com.byagowi.persiancalendar.util.CalendarType;
import com.byagowi.persiancalendar.util.CalendarUtils;
import com.byagowi.persiancalendar.util.UIUtils;
import com.byagowi.persiancalendar.util.Utils;
import com.byagowi.persiancalendar.view.CalendarsView;
import com.byagowi.persiancalendar.view.activity.MainActivity;
import com.byagowi.persiancalendar.view.dialog.SelectDayDialog;
import com.cepmuvakkit.times.posAlgo.SunMoonPosition;
import com.github.praytimes.Clock;
import com.github.praytimes.Coordinate;
import com.github.praytimes.PrayTimes;
import com.github.praytimes.PrayTimesCalculator;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.ActivityCompat;
import androidx.databinding.DataBindingUtil;
import androidx.viewpager.widget.ViewPager;
import calendar.AbstractDate;
import calendar.CivilDate;
import calendar.IslamicDate;
import calendar.PersianDate;
import dagger.android.support.DaggerFragment;

import static com.byagowi.persiancalendar.Constants.CALENDAR_EVENT_ADD_MODIFY_REQUEST_CODE;
import static com.byagowi.persiancalendar.Constants.PREF_HOLIDAY_TYPES;

public class CalendarFragment extends DaggerFragment implements View.OnClickListener {
    @Inject
    AppDependency appDependency; // same object from App
    @Inject
    MainActivityDependency mainActivityDependency; // same object from MainActivity
    boolean firstTime = true;
    private Calendar calendar = Calendar.getInstance();
    private Coordinate coordinate;
    private PrayTimesCalculator prayTimesCalculator;
    private int viewPagerPosition;
    private FragmentCalendarBinding mainBinding;
    private CalendarsView calendarsView;
    private OwghatTabContentBinding owghatBinding;
    private EventsTabContentBinding eventsBinding;
    private long lastSelectedJdn = -1;
    private ViewPager.OnPageChangeListener changeListener = new ViewPager.SimpleOnPageChangeListener() {
        @Override
        public void onPageSelected(int position) {
            appDependency.getLocalBroadcastManager().sendBroadcast(
                    new Intent(Constants.BROADCAST_INTENT_TO_MONTH_FRAGMENT)
                            .putExtra(Constants.BROADCAST_FIELD_TO_MONTH_FRAGMENT,
                                    CalendarAdapter.positionToOffset(position))
                            .putExtra(Constants.BROADCAST_FIELD_SELECT_DAY_JDN, lastSelectedJdn));

            calendarsView.showTodayIcon();
        }

    };
    private SearchView mSearchView;
    private SearchView.SearchAutoComplete mSearchAutoComplete;

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {

        Context context = mainActivityDependency.getMainActivity();

        setHasOptionsMenu(true);

        mainBinding = DataBindingUtil.inflate(inflater, R.layout.fragment_calendar, container,
                false);
        viewPagerPosition = 0;

        boolean isRTL = UIUtils.isRTL(context);
        List<String> titles = new ArrayList<>();
        List<View> tabs = new ArrayList<>();

        titles.add(getString(R.string.calendar));
        calendarsView = new CalendarsView(context);
        calendarsView.setOnCalendarsViewExpandListener(() -> mainBinding.cardsViewPager.measureCurrentView(calendarsView));
        calendarsView.setOnTodayButtonClickListener(this::bringTodayYearMonth);
        tabs.add(calendarsView);

        titles.add(getString(R.string.events));
        eventsBinding = DataBindingUtil.inflate(inflater, R.layout.events_tab_content, container, false);
        tabs.add(eventsBinding.getRoot());

        coordinate = Utils.getCoordinate(context);
        if (coordinate != null) {
            titles.add(getString(R.string.owghat));
            owghatBinding = DataBindingUtil.inflate(inflater, R.layout.owghat_tab_content, container, false);
            tabs.add(owghatBinding.getRoot());
            owghatBinding.getRoot().setOnClickListener(this);
        }

        mainBinding.cardsViewPager.setAdapter(new CardTabsAdapter(getChildFragmentManager(),
                appDependency, tabs, titles));
        mainBinding.tabLayout.setupWithViewPager(mainBinding.cardsViewPager);

        prayTimesCalculator = new PrayTimesCalculator(Utils.getCalculationMethod());
        mainBinding.calendarViewPager.setAdapter(new CalendarAdapter(getChildFragmentManager(), isRTL));
        CalendarAdapter.gotoOffset(mainBinding.calendarViewPager, 0);

        mainBinding.calendarViewPager.addOnPageChangeListener(changeListener);

        int lastTab = appDependency.getSharedPreferences()
                .getInt(Constants.LAST_CHOSEN_TAB_KEY, Constants.CALENDARS_TAB);
        if (lastTab >= tabs.size()) {
            lastTab = Constants.CALENDARS_TAB;
        }

        mainBinding.cardsViewPager.setCurrentItem(lastTab, false);

        AbstractDate today = CalendarUtils.getTodayOfCalendar(Utils.getMainCalendar());
        mainActivityDependency.getMainActivity().setTitleAndSubtitle(CalendarUtils.getMonthName(today),
                Utils.formatNumber(today.getYear()));

        if (coordinate != null) {
            String cityName = Utils.getCityName(context, false);
            if (!TextUtils.isEmpty(cityName)) {
                owghatBinding.owghatText.setText(cityName);
            }

            // Easter egg to test AthanActivity
            owghatBinding.owghatText.setOnClickListener(this);
            owghatBinding.owghatText.setOnLongClickListener(v -> {
                Utils.startAthan(context, "FAJR");
                return true;
            });
        }

        return mainBinding.getRoot();
    }

    void changeMonth(int position) {
        mainBinding.calendarViewPager.setCurrentItem(
                mainBinding.calendarViewPager.getCurrentItem() + position, true);
    }

    public void selectDay(long jdn) {
        lastSelectedJdn = jdn;
        calendarsView.showCalendars(jdn, Utils.getMainCalendar(), Utils.getEnabledCalendarTypes());
        setOwghat(jdn, CalendarUtils.getTodayJdn() == jdn);
        showEvent(jdn);
    }

    public void addEventOnCalendar(long jdn) {
        MainActivity activity = mainActivityDependency.getMainActivity();

        CivilDate civil = new CivilDate(jdn);
        Calendar time = Calendar.getInstance();
        time.set(civil.getYear(), civil.getMonth() - 1, civil.getDayOfMonth());
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            UIUtils.askForCalendarPermission(activity);
        } else {
            try {
                startActivityForResult(
                        new Intent(Intent.ACTION_INSERT)
                                .setData(CalendarContract.Events.CONTENT_URI)
                                .putExtra(CalendarContract.Events.DESCRIPTION, CalendarUtils.dayTitleSummary(
                                        CalendarUtils.getDateFromJdnOfCalendar(Utils.getMainCalendar(), jdn)))
                                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,
                                        time.getTimeInMillis())
                                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,
                                        time.getTimeInMillis())
                                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true),
                        CALENDAR_EVENT_ADD_MODIFY_REQUEST_CODE);
            } catch (Exception e) {
                Toast.makeText(activity, R.string.device_calendar_does_not_support, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        MainActivity activity = mainActivityDependency.getMainActivity();

        if (requestCode == CALENDAR_EVENT_ADD_MODIFY_REQUEST_CODE) {
            if (Utils.isShowDeviceCalendarEvents()) {
                appDependency.getLocalBroadcastManager().sendBroadcast(
                        new Intent(Constants.BROADCAST_INTENT_TO_MONTH_FRAGMENT)
                                .putExtra(Constants.BROADCAST_FIELD_TO_MONTH_FRAGMENT,
                                        calculateViewPagerPositionFromJdn(lastSelectedJdn))
                                .putExtra(Constants.BROADCAST_FIELD_EVENT_ADD_MODIFY, true)
                                .putExtra(Constants.BROADCAST_FIELD_SELECT_DAY_JDN, lastSelectedJdn));
            } else {
                if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR)
                        != PackageManager.PERMISSION_GRANTED) {
                    UIUtils.askForCalendarPermission(activity);
                } else {
                    UIUtils.toggleShowDeviceCalendarOnPreference(activity, true);
                    activity.restartActivity();
                }
            }
        }
    }

    private SpannableString formatClickableEventTitle(DeviceCalendarEvent event) {
        String title = UIUtils.formatDeviceCalendarEventTitle(event);
        SpannableString ss = new SpannableString(title);
        ClickableSpan clickableSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View textView) {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_VIEW)
                                    .setData(ContentUris.withAppendedId(
                                            CalendarContract.Events.CONTENT_URI, event.getId())),
                            CALENDAR_EVENT_ADD_MODIFY_REQUEST_CODE);
                } catch (Exception e) { // Should be ActivityNotFoundException but we don't care really
                    Toast.makeText(mainActivityDependency.getMainActivity(),
                            R.string.device_calendar_does_not_support, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                super.updateDrawState(ds);
                String color = event.getColor();
                if (!TextUtils.isEmpty(color)) {
                    try {
                        ds.setColor(Integer.parseInt(color));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        ss.setSpan(clickableSpan, 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    private SpannableStringBuilder getDeviceEventsTitle(List<AbstractEvent> dayEvents) {
        SpannableStringBuilder titles = new SpannableStringBuilder();
        boolean first = true;

        for (AbstractEvent event : dayEvents)
            if (event instanceof DeviceCalendarEvent) {
                if (first)
                    first = false;
                else
                    titles.append("\n");

                titles.append(formatClickableEventTitle((DeviceCalendarEvent) event));
            }

        return titles;
    }

    private void showEvent(long jdn) {
        List<AbstractEvent> events = Utils.getEvents(jdn,
                CalendarUtils.readDayDeviceEvents(mainActivityDependency.getMainActivity(), jdn));
        String holidays = Utils.getEventsTitle(events, true, false, false, false);
        String nonHolidays = Utils.getEventsTitle(events, false, false, false, false);
        SpannableStringBuilder deviceEvents = getDeviceEventsTitle(events);
        StringBuilder contentDescription = new StringBuilder();

        eventsBinding.holidayTitle.setVisibility(View.GONE);
        eventsBinding.deviceEventTitle.setVisibility(View.GONE);
        eventsBinding.eventTitle.setVisibility(View.GONE);
        eventsBinding.eventMessage.setVisibility(View.GONE);
        eventsBinding.noEvent.setVisibility(View.VISIBLE);

        if (!TextUtils.isEmpty(holidays)) {
            eventsBinding.noEvent.setVisibility(View.GONE);
            eventsBinding.holidayTitle.setText(holidays);
            String holidayContent = getString(R.string.holiday_reason) + "\n" + holidays;
            eventsBinding.holidayTitle.setContentDescription(holidayContent);
            contentDescription.append(holidayContent);
            eventsBinding.holidayTitle.setVisibility(View.VISIBLE);
        }

        if (deviceEvents.length() != 0) {
            eventsBinding.noEvent.setVisibility(View.GONE);
            eventsBinding.deviceEventTitle.setText(deviceEvents);
            contentDescription.append("\n");
            contentDescription.append(getString(R.string.show_device_calendar_events));
            contentDescription.append("\n");
            contentDescription.append(deviceEvents);
            eventsBinding.deviceEventTitle.setMovementMethod(LinkMovementMethod.getInstance());

            eventsBinding.deviceEventTitle.setVisibility(View.VISIBLE);
        }


        if (!TextUtils.isEmpty(nonHolidays)) {
            eventsBinding.noEvent.setVisibility(View.GONE);
            eventsBinding.eventTitle.setText(nonHolidays);
            contentDescription.append("\n");
            contentDescription.append(getString(R.string.events));
            contentDescription.append("\n");
            contentDescription.append(nonHolidays);

            eventsBinding.eventTitle.setVisibility(View.VISIBLE);
        }

        SpannableStringBuilder messageToShow = new SpannableStringBuilder();

        Set<String> enabledTypes = appDependency.getSharedPreferences()
                .getStringSet(PREF_HOLIDAY_TYPES, new HashSet<>());
        if (enabledTypes.size() == 0) {
            eventsBinding.noEvent.setVisibility(View.GONE);
            if (!TextUtils.isEmpty(messageToShow))
                messageToShow.append("\n");

            String title = getString(R.string.warn_if_events_not_set);
            SpannableString ss = new SpannableString(title);
            ClickableSpan clickableSpan = new ClickableSpan() {
                @Override
                public void onClick(@NonNull View textView) {
                    mainActivityDependency.getMainActivity().bringPreferences();
                }
            };
            ss.setSpan(clickableSpan, 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            messageToShow.append(ss);

            contentDescription.append("\n");
            contentDescription.append(title);
        }

        if (!TextUtils.isEmpty(messageToShow)) {
            eventsBinding.eventMessage.setText(messageToShow);
            eventsBinding.eventMessage.setMovementMethod(LinkMovementMethod.getInstance());

            eventsBinding.eventMessage.setVisibility(View.VISIBLE);
        }

        eventsBinding.getRoot().setContentDescription(contentDescription);
    }

    private void setOwghat(long jdn, boolean isToday) {
        if (coordinate == null) {
            return;
        }

        CivilDate civilDate = new CivilDate(jdn);
        calendar.set(civilDate.getYear(), civilDate.getMonth() - 1, civilDate.getDayOfMonth());
        Date date = calendar.getTime();

        PrayTimes prayTimes = prayTimesCalculator.calculate(date, coordinate);

        owghatBinding.imsak.setText(UIUtils.getFormattedClock(prayTimes.getImsakClock()));
        Clock sunriseClock = prayTimes.getFajrClock();
        owghatBinding.fajr.setText(UIUtils.getFormattedClock(sunriseClock));
        owghatBinding.sunrise.setText(UIUtils.getFormattedClock(prayTimes.getSunriseClock()));
        Clock midddayClock = prayTimes.getDhuhrClock();
        owghatBinding.dhuhr.setText(UIUtils.getFormattedClock(midddayClock));
        owghatBinding.asr.setText(UIUtils.getFormattedClock(prayTimes.getAsrClock()));
        owghatBinding.sunset.setText(UIUtils.getFormattedClock(prayTimes.getSunsetClock()));
        Clock maghribClock = prayTimes.getMaghribClock();
        owghatBinding.maghrib.setText(UIUtils.getFormattedClock(maghribClock));
        owghatBinding.isgha.setText(UIUtils.getFormattedClock(prayTimes.getIshaClock()));
        owghatBinding.midnight.setText(UIUtils.getFormattedClock(prayTimes.getMidnightClock()));

        double moonPhase = 1;
        try {
            moonPhase = new SunMoonPosition(CalendarUtils.getTodayJdn(), coordinate.getLatitude(),
                    coordinate.getLongitude(), 0, 0).getMoonPhase();
        } catch (Exception e) {
            e.printStackTrace();
        }
        owghatBinding.svPlot.setSunriseSunsetMoonPhase(prayTimes, moonPhase);

        if (isToday) {
            owghatBinding.svPlot.setVisibility(View.VISIBLE);
            if (mainBinding.cardsViewPager.getCurrentItem() == Constants.OWGHAT_TAB) {
                owghatBinding.svPlot.startAnimate(true);
            }
        } else {
            owghatBinding.svPlot.setVisibility(View.GONE);
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.owghat_text:
            case R.id.owghat_content:

                boolean isOpenOwghatCommand = owghatBinding.sunriseLayout.getVisibility() == View.GONE;

                owghatBinding.moreOwghat.setImageResource(isOpenOwghatCommand
                        ? R.drawable.ic_keyboard_arrow_up
                        : R.drawable.ic_keyboard_arrow_down);
                owghatBinding.imsakLayout.setVisibility(isOpenOwghatCommand ? View.VISIBLE : View.GONE);
                owghatBinding.sunriseLayout.setVisibility(isOpenOwghatCommand ? View.VISIBLE : View.GONE);
                owghatBinding.asrLayout.setVisibility(isOpenOwghatCommand ? View.VISIBLE : View.GONE);
                owghatBinding.sunsetLayout.setVisibility(isOpenOwghatCommand ? View.VISIBLE : View.GONE);
                owghatBinding.ishaLayout.setVisibility(isOpenOwghatCommand ? View.VISIBLE : View.GONE);
                owghatBinding.midnightLayout.setVisibility(isOpenOwghatCommand ? View.VISIBLE : View.GONE);

                mainBinding.cardsViewPager.measureCurrentView(owghatBinding.getRoot());

                if (lastSelectedJdn == -1)
                    lastSelectedJdn = CalendarUtils.getTodayJdn();

                break;
        }
    }

    private void bringTodayYearMonth() {
        lastSelectedJdn = -1;
        appDependency.getLocalBroadcastManager().sendBroadcast(
                new Intent(Constants.BROADCAST_INTENT_TO_MONTH_FRAGMENT)
                        .putExtra(Constants.BROADCAST_FIELD_TO_MONTH_FRAGMENT,
                                Constants.BROADCAST_TO_MONTH_FRAGMENT_RESET_DAY)
                        .putExtra(Constants.BROADCAST_FIELD_SELECT_DAY_JDN, -1));

        CalendarAdapter.gotoOffset(mainBinding.calendarViewPager, 0);

        selectDay(CalendarUtils.getTodayJdn());
    }

    public void bringDate(long jdn) {
        Context context = getContext();
        if (context == null) return;

        viewPagerPosition = calculateViewPagerPositionFromJdn(jdn);
        CalendarAdapter.gotoOffset(mainBinding.calendarViewPager, viewPagerPosition);

        selectDay(jdn);

        if (Utils.isTalkBackEnabled()) {
            long todayJdn = CalendarUtils.getTodayJdn();
            if (jdn != todayJdn) {
                Toast.makeText(context, CalendarUtils.getA11yDaySummary(context, jdn,
                        false, null, true,
                        true, true), Toast.LENGTH_SHORT).show();
            }
        }

        appDependency.getLocalBroadcastManager().sendBroadcast(
                new Intent(Constants.BROADCAST_INTENT_TO_MONTH_FRAGMENT)
                        .putExtra(Constants.BROADCAST_FIELD_TO_MONTH_FRAGMENT, viewPagerPosition)
                        .putExtra(Constants.BROADCAST_FIELD_SELECT_DAY_JDN, jdn));
    }

    private int calculateViewPagerPositionFromJdn(long jdn) {
        CalendarType mainCalendar = Utils.getMainCalendar();
        AbstractDate today = CalendarUtils.getTodayOfCalendar(mainCalendar);
        AbstractDate date = CalendarUtils.getDateFromJdnOfCalendar(mainCalendar, jdn);
        return (today.getYear() - date.getYear()) * 12 + today.getMonth() - date.getMonth();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        menu.clear();
        inflater.inflate(R.menu.calendar_menu_buttons, menu);

        mSearchView = (SearchView) menu.findItem(R.id.search).getActionView();
        mSearchView.setOnSearchClickListener(v -> {
            if (mSearchAutoComplete != null) mSearchAutoComplete.setOnItemClickListener(null);

            Context context = getContext();
            if (context == null) return;

            mSearchAutoComplete = mSearchView.findViewById(androidx.appcompat.R.id.search_src_text);
            mSearchAutoComplete.setHint(R.string.search_in_events);

            ArrayAdapter<AbstractEvent> eventsAdapter = new ArrayAdapter<>(context,
                    R.layout.suggestion, android.R.id.text1);
            eventsAdapter.addAll(Utils.getAllEnabledEvents());
            eventsAdapter.addAll(CalendarUtils.getAllEnabledAppointments(context));
            mSearchAutoComplete.setAdapter(eventsAdapter);
            mSearchAutoComplete.setOnItemClickListener((parent, view, position, id) -> {
                AbstractEvent ev = (AbstractEvent) parent.getItemAtPosition(position);
                AbstractDate date = ev.getDate();
                CalendarType type = CalendarUtils.getCalendarTypeFromDate(date);
                AbstractDate today = CalendarUtils.getTodayOfCalendar(type);
                int year = date.getYear();
                if (year == -1) {
                    year = today.getYear() + (date.getMonth() < today.getMonth() ? 1 : 0);
                }
                bringDate(CalendarUtils.getDateOfCalendar(type, year, date.getMonth(), date.getDayOfMonth()).toJdn());
                mSearchView.onActionViewCollapsed();
            });
        });
    }

    private void destroySearchView() {
        if (mSearchView != null) {
            mSearchView.setOnSearchClickListener(null);
            mSearchView = null;
        }

        if (mSearchAutoComplete != null) {
            mSearchAutoComplete.setAdapter(null);
            mSearchAutoComplete.setOnItemClickListener(null);
            mSearchAutoComplete = null;
        }
    }

    @Override
    public void onDestroyOptionsMenu() {
        destroySearchView();
        super.onDestroyOptionsMenu();
    }

    @Override
    public void onDestroy() {
        destroySearchView();
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.go_to:
                SelectDayDialog.newInstance(lastSelectedJdn).show(getChildFragmentManager(),
                        SelectDayDialog.class.getName());
                break;
            case R.id.today:
                bringTodayYearMonth();
                break;
            case R.id.add_event:
                if (lastSelectedJdn == -1)
                    lastSelectedJdn = CalendarUtils.getTodayJdn();

                addEventOnCalendar(lastSelectedJdn);
                break;
            default:
                break;
        }
        return true;
    }

    int getViewPagerPosition() {
        return viewPagerPosition;
    }

    public boolean closeSearch() {
        if (mSearchView != null && !mSearchView.isIconified()) {
            mSearchView.onActionViewCollapsed();
            return true;
        }
        return false;
    }

}
