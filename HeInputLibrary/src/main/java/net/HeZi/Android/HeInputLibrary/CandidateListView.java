/*
  * Copyright (c) 2015 Guilin Ouyang. All rights reserved.
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *      http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */

package net.HeZi.Android.HeInputLibrary;

import java.util.HashMap;
import java.util.List;

import net.HeZi.Android.HeInputLibrary.HeInputService;
import net.HeZi.Android.HeInputLibrary.R;
import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.SimpleAdapter;

public class CandidateListView extends ListView
		implements OnItemClickListener
{
	private HeInputService mService;

	private Context cxt;

	protected CandidateItemInteractionListener itemListener;

	private GestureDetector mGestureDetector;
	private static final int SWIPE_MIN_DISTANCE = 80;
	private static final int SWIPE_MAX_OFF_PATH = 200;
	private static final int SWIPE_THRESHOLD_VELOCITY = 100;

	public interface CandidateItemInteractionListener {

		public void onItemInteraction(int itemIndexOnThePage);
	}

	public CandidateListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		cxt = context;
		this.setClickable(false);
		setOnItemClickListener(this);

		mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
				if (e1 == null || e2 == null || mService == null) return false;
				float diffY = e2.getY() - e1.getY();
				float diffX = e2.getX() - e1.getX();
				if (Math.abs(diffY) > SWIPE_MAX_OFF_PATH) return false;
				if (Math.abs(diffX) < SWIPE_MIN_DISTANCE) return false;
				if (Math.abs(velocityX) < SWIPE_THRESHOLD_VELOCITY) return false;

				if (diffX > 0) {
					mService.pageSwiped(-1); // swipe right -> previous page
				} else {
					mService.pageSwiped(1);  // swipe left -> next page
				}
				return true;
			}
		});
	}

	public void printListViewPage(List<HashMap<String, String>> onePageList, int itemIndex)
	{
		String[] columns = new String[] {"ZiCi","English"};
		int[] to = new int[] { R.id.ziCiText, R.id.shuMaPrompt };

		SimpleAdapter adapter = new SimpleAdapter(cxt, onePageList, R.layout.item, columns, to);

		this.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
		this.setAdapter(adapter);
		this.setItemChecked(itemIndex, true);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (mGestureDetector.onTouchEvent(event)) {
			return true;
		}
		return super.onTouchEvent(event);
	}

	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
	}

	public void setService(HeInputService listener) {
		mService = listener;
		itemListener = listener;
	}

	@Override
	public void setSelection(int position) {
		super.setSelection(position);
	}

	@Override
	public void onItemClick(AdapterView<?> listView, View view,
	                        int position, long id) {
		itemListener.onItemInteraction(position);
	}
}
