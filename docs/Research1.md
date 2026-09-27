# The following are observations that were made on the mobile app and need actions.
#Keep to brainbox.md rules every single minute.

## Landing Screen
1. CBC PROJECTS: this feature is only accessible by aunthenticated users which is good.we need to;
    1. investigate the guarded feature for production readiness, premium UX practices and UI flow, all this should be audited and fixed. take a standing point of a user, what users sees and experiences is what we should improve along with pipelines he cant see
    2. fork a landing page featuree from this one that allows unauthenticated to browse and explore projects without CRUDing, the same flow as landing page schools to be followed (card->list->details) when in details, users can 'Fund', 'Track', 'comment' upvote/downvote and so on. fund button opens a dialog with 'To fund or promote the owner(s) of this project, contact the school administration for more info. the dialog button 'Take me to this school' should auto navigate to school details screen (same schools from landing screen) from which the owner of the project belongs if the school is not there, navigate to school list and inform the users 'The school has not yet updated their information on this page. Track button opens a dialog from which the user would input email to be notified when there is an update on this project and closes upon submision with success/failure feedback. comments and upvotes/downvotes to work seemless. 
    3. since brainbox has not yet built the backend fully, feel free to build ontop/rebuild or any method to produce quality work but document API changes to keep backend team up to date. databse migrations should not worry you since the app has no existing user base

2.  
    1. Trending school. when going through the list of schools, the 'view all details' is duplicated on the actual details dialog. fix
    2. we are now optimising for real worl usage production not mocking, so audit everything from network pipeline, data pipeline, error handling, empty state for whole school and sub details, UI,UX and app size optimization.
    3. images are supported but are they handked and displayed as expected in the industry?

3. brainbox News. the news are more like stubbed. the 'more' destination list shows the news but users cannot read articles, upvote/downvote comment or share or report. follow same auditing semantics


4. we have a card that shows: 50K+ students / 500+ school /4.9/5 rating. remove this
5. ABOUT BRAINBOX leads to a page where we have emojis used instead of icons. if no icons to populate,leave without any.

## Theme pallete
1. brainbox has many theme in this pallete where the variation of light theme is only one (corporate ligt) and many variations of 'dark' theme (the rest) we need to balance by editing and renaming these to balance the variation. edit from a point of (a proffesional designer who must balance between attractive and readable) to balance.

## side notch
this one has a blur-screen on tap but the amount of blur is not enough to make the side notch item names fully readable. UX is challanged here

## DASHBOARD
just below today's mission card we have horizontal-scrolling chips (daily quiz, AI Coach...etc) audit all of them one by one for proper logic and use, remove meaningless stubs, bettweto ship half of the features but fully usable than a mere promise. 
