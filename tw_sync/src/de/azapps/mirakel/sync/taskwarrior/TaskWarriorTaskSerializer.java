/*******************************************************************************
 * Mirakel is an Android App for managing your ToDo-Lists
 * 
 * Copyright (c) 2013-2014 Anatolij Zelenin, Georg Semmler.
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package de.azapps.mirakel.sync.taskwarrior;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.database.Cursor;
import android.util.Pair;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import de.azapps.mirakel.DefinitionsHelper.SYNC_STATE;
import de.azapps.mirakel.helper.DateTimeHelper;
import de.azapps.mirakel.model.MirakelContentProvider;
import de.azapps.mirakel.model.recurring.Recurring;
import de.azapps.mirakel.model.tags.Tag;
import de.azapps.mirakel.model.task.Task;
import de.azapps.mirakel.sync.R;
import de.azapps.tools.Log;

public class TaskWarriorTaskSerializer implements JsonSerializer<Task> {

	private static final String TAG = "TaskWarriorTaskSerializer";
	private final Context mContext;

	public TaskWarriorTaskSerializer(final Context ctx) {
		this.mContext = ctx;
	}

	private String formatCal(final Calendar c) {
		final SimpleDateFormat df = new SimpleDateFormat(
				this.mContext.getString(R.string.TWDateFormat));
		if (c.getTimeInMillis() < 0) {
			c.setTimeInMillis(10);
		}
		return df.format(c.getTime());
	}

	private static String escape(final String string) {
		return string.replace("\"", "\\\"");
	}

	private static String cleanQuotes(String str) {
		// call this only if string starts and ands with "
		// additional keys has this
		if (str.startsWith("\"") || str.startsWith("'")) {
			str = str.substring(1);
		}
		if (str.endsWith("\"") || str.endsWith("'")) {
			str = str.substring(0, str.length() - 1);
		}
		return str;
	}

	@Override
	public JsonElement serialize(final Task task, final Type arg1,
			final JsonSerializationContext arg2) {
		final JsonObject json = new JsonObject();
		final Map<String, String> additionals = task.getAdditionalEntries();
		boolean isMaster = false;
		if (task.getRecurring() != null) {
			final Cursor c = MirakelContentProvider.getReadableDatabase()
					.query(Recurring.TW_TABLE, new String[] { "count(*)" },
							"child=?", new String[] { task.getId() + "" },
							null, null, null);
			c.moveToFirst();
			if (c.getLong(0) == 0) {
				isMaster = true;
			}
		}
		final Pair<String, String> s = getStatus(task, additionals, isMaster);
		final String status = s.second;
		final String end = s.first;

		String priority = null;
		switch (task.getPriority()) {
		case -2:
		case -1:
			priority = "L";
			break;
		case 1:
			priority = "M";
			break;
		case 2:
			priority = "H";
			break;
		default:
			break;
		}

		String uuid = task.getUUID();
		if (uuid == null || uuid.trim().equals("")) {
			uuid = java.util.UUID.randomUUID().toString();
			task.setUUID(uuid);
			task.save(false);
		}
		json.addProperty("uuid", uuid);
		json.addProperty("status", status);
		json.addProperty("entry", formatCalUTC(task.getCreatedAt()));
		json.addProperty("description", escape(task.getName()));

		if (task.getDue() != null) {
			json.addProperty("due", formatCalUTC(task.getDue()));

		}
		if (task.getList() != null
				&& !additionals.containsKey(TaskWarriorSync.NO_PROJECT)) {
			json.addProperty("project", task.getList().getName());
		}
		if (priority != null) {
			json.addProperty("priority", priority);
			if ("L".equals(priority) && task.getPriority() != -2) {
				json.addProperty("priorityNumber", task.getPriority());
			}
		}
		if (task.getUpdatedAt() != null) {
			json.addProperty("modified", formatCalUTC(task.getUpdatedAt()));
		}
		if (task.getReminder() != null) {
			json.addProperty("reminder", formatCalUTC(task.getReminder()));
		}

		if (end != null) {
			json.addProperty("end", end);
		}
		if (task.getProgress() != 0) {
			json.addProperty("progress", task.getProgress());
		}
		// Tags
		if (task.getTags().size() > 0) {
			final JsonArray tags = new JsonArray();
			for (final Tag t : task.getTags()) {
				// taskwarrior does not like whitespaces
				tags.add(new JsonPrimitive(t.getName().trim().replace(" ", "_")));
			}
			json.add("tags", tags);
		}
		// End Tags
		// Annotations
		if (task.getContent() != null && !task.getContent().equals("")) {
			final JsonArray annotations = new JsonArray();
			/*
			 * An annotation in taskd is a line of content in Mirakel!
			 */
			final String annotationsList[] = escape(task.getContent()).split(
					"\n");
			final Calendar d = task.getUpdatedAt();

			for (final String a : annotationsList) {
				final JsonObject line = new JsonObject();
				line.addProperty("entry", formatCalUTC(task.getUpdatedAt()));
				line.addProperty("description", a.replace("\n", ""));
				annotations.add(line);
				d.add(Calendar.SECOND, 1);
			}
			json.add("annotations", annotations);
		}
		// Anotations end
		// TW.depends==Mirakel.subtasks!
		// Dependencies
		if (task.getSubtaskCount() > 0) {
			boolean first1 = true;
			String depends = "";
			for (final Task subtask : task.getSubtasks()) {
				if (first1) {
					first1 = false;
				} else {
					depends += ",";
				}
				depends += subtask.getUUID();
			}
			json.addProperty("depends", depends);
		}
		// recurring tasks must have a due
		if (task.getRecurring() != null && task.getDue() != null) {
			handleRecurrence(json, task.getRecurring());
			if (isMaster) {
				String mask = "";
				final Cursor c = MirakelContentProvider.getReadableDatabase()
						.query(Recurring.TW_TABLE,
								new String[] { "child", "offsetCount" },
								"parent=?", new String[] { task.getId() + "" },
								null, null, "offsetCount ASC");
				c.moveToFirst();
				if (c.getCount() > 0) {
					int oldOffset = -1;
					do {
						final int currentOffset = c.getInt(1);
						if(currentOffset <= oldOffset) {
							final long childId = c.getLong(0);
							// This should not happen – it means that one offset is twice in the DB
							final Task child = Task.get(childId,true);
							if(child!=null) {
								child.destroy(true);
							} else {
								// Whoa there is some garbage which we should destroy!
								Task.destroyRecurrenceGarbageForTask(childId);
							}
							continue;
						}
						while (++oldOffset < currentOffset) {
							mask += "X";
						}
						final Task child = Task.get(c.getLong(0));
						if (child == null) {
							Log.wtf(TAG, "childtask is null");
							mask += "X";
						} else {
							mask += getRecurrenceStatus(getStatus(child,
									child.getAdditionalEntries(), false).second);
						}
					} while (c.moveToNext());
				}
				c.close();
				json.addProperty("mask", mask);
			} else {
				final Cursor c = MirakelContentProvider.getReadableDatabase()
						.query(Recurring.TW_TABLE,
								new String[] { "parent", "offsetCount" },
								"child=?", new String[] { task.getId() + "" },
								null, null, null);
				c.moveToFirst();
				if (c.getCount() > 0) {
					final Task master = Task.get(c.getLong(0));
					if (master == null) {
						// The parent is gone. This should not happen and we
						// should delete the child then
						task.destroy();
					} else {
						json.addProperty("parent", master.getUUID());
						json.addProperty("imask", c.getInt(1));
					}
				} else {
					Log.wtf(TAG, "no master found, but there must be a master");
				}
				c.close();
			}
		}
		// end Dependencies
		// Additional Strings
		if (additionals != null) {
			for (final String key : additionals.keySet()) {
				if (!key.equals(TaskWarriorSync.NO_PROJECT)
						&& !key.equals("status")) {
					json.addProperty(key, cleanQuotes(additionals.get(key)));
				}
			}
		}
		// end Additional Strings
		return json;
	}

	private static String getRecurrenceStatus(final String s) {
		switch (s) {
		case "recurring":
		case "pending":
			return "-";
		case "completed":
			return "+";
		case "deleted":
			return "X";
		case "waiting":
			return "W";
		default:
			break;
		}
		return "";
	}

	static void handleRecurrence(final JsonObject json, final Recurring r) {
		if (r == null) {
			Log.wtf(TAG, "recurring is null");
			return;
		}
		if (r.getWeekdays().size() > 0) {

			switch (r.getWeekdays().size()) {
			case 1:
				json.addProperty("recur", "weekly");
				return;
			case 7:
				json.addProperty("recur", "daily");
				return;
			case 5:
				final List<Integer> weekdays = r.getWeekdays();
				for (Integer i = Calendar.MONDAY; i <= Calendar.FRIDAY; i++) {
					if (!weekdays.contains(i)) {
						Log.w(TAG, "unsupported recurrence");
						return;
					}
				}
				json.addProperty("recur", "weekdays");
				return;
			default:
				Log.w(TAG, "unsupported recurrence");
				return;
			}
		}
		long interval = r.getInterval() / (1000 * 60);
		if (interval >= 60 * 24 * 365) {
			interval /= 60 * 24 * 365;
			json.addProperty("recur", interval + "years");
		} else if (interval >= 60 * 24 * 30) {
			interval /= 60 * 24 * 30;
			json.addProperty("recur", interval + "months");
		} else if (interval >= 60 * 24) {
			interval /= 60 * 24;
			json.addProperty("recur", interval + "days");
		} else if (interval >= 60) {
			interval /= 60;
			json.addProperty("recur", interval + "hours");
		} else {
			json.addProperty("recur", interval + "mins");
		}

	}

	private Pair<String, String> getStatus(final Task task,
			final Map<String, String> additionals, final boolean isMaster) {
		String end = null;
		String status = "pending";
		final Calendar now = new GregorianCalendar();
		now.setTimeInMillis(now.getTimeInMillis()
				- DateTimeHelper.getTimeZoneOffset(true, now));
		if (task.getSyncState() == SYNC_STATE.DELETE) {
			status = "deleted";
			end = formatCal(now);
		} else if (task.isDone()) {
			status = "completed";
			if (additionals.containsKey("end")) {
				end = cleanQuotes(additionals.get("end"));
			} else {
				end = formatCal(now);
			}
		} else if (task.getRecurring() != null && isMaster) {
			status = "recurring";
		} else if (task.getAdditionalEntries().containsKey("status")) {
			status = cleanQuotes(task.getAdditionalEntries().get("status"));
		}
		return new Pair<String, String>(end, status);
	}

	private String formatCalUTC(final Calendar c) {
		return formatCal(DateTimeHelper.getUTCCalendar(c));
	}
}