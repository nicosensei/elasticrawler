/**
 * 
 */
package com.github.nicosensei.elasticrawler.crawler;

import java.util.List;

import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.nicosensei.elasticrawler.crawler.CrawlResult.StatusCode;

import edu.uci.ics.crawler4j.crawler.CrawlConfig;
import edu.uci.ics.crawler4j.crawler.Page;
import edu.uci.ics.crawler4j.crawler.Page.Type;
import edu.uci.ics.crawler4j.fetcher.CustomFetchStatus;
import edu.uci.ics.crawler4j.fetcher.PageFetchResult;
import edu.uci.ics.crawler4j.fetcher.PageFetcher;
import edu.uci.ics.crawler4j.parser.Parser;
import edu.uci.ics.crawler4j.robotstxt.RobotstxtServer;

/**
 * A generic crawler thread abstract implementation.
 * 
 *  Heavily based on Crawler4j's {@link WebCrawler}, but intended to run continuously.
 * 
 * @author nicolas
 *
 */
public abstract class CrawlerThread implements Runnable {

	protected static final Logger LOGGER = LoggerFactory.getLogger(CrawlerThread.class);

	private String id;

	private int pullSize;

	private CrawlConfig crawlConfig;

	/**
	 * Time to wait (in milliseconds) between two pulls from the frontier if the previous
	 * pull did not bring URLs.
	 */
	private long pullRetryDelay;

	/**
	 * The parser that is used by this crawler instance to parse the content of
	 * the fetched pages.
	 */
	private Parser parser;

	/**
	 * The fetcher that is used by this crawler instance to fetch the content of
	 * pages from the web.
	 */
	private PageFetcher pageFetcher;

	/**
	 * The RobotstxtServer instance that is used by this crawler instance to
	 * determine whether the crawler is allowed to crawl the content of each
	 * page.
	 */
	private RobotstxtServer robotsTxtServer;

	/**
	 * The Frontier object that manages the crawl queues.
	 */
	private Frontier frontier;

	/**
	 * The crawl history store.
	 */
	private CrawlHistory crawlHistory;

	public CrawlerThread(
			final String id,
			final CrawlConfig crawlConfig,
			final int pullSize,
			final Frontier frontier,
			final RobotstxtServer robotsTxtServer,
			final PageFetcher pageFetcher,
			final Parser parser) {
		this.id = id;
		this.crawlConfig = crawlConfig;
		this.pullSize = pullSize;
		this.frontier = frontier;
		this.robotsTxtServer = robotsTxtServer;
		this.pageFetcher = pageFetcher;
		this.parser = parser;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public int getPullSize() {
		return pullSize;
	}

	public void setPullSize(int pullSize) {
		this.pullSize = pullSize;
	}

	public CrawlConfig getCrawlConfig() {
		return crawlConfig;
	}

	public void setCrawlConfig(CrawlConfig crawlConfig) {
		this.crawlConfig = crawlConfig;
	}

	public long getPullRetryDelay() {
		return pullRetryDelay;
	}

	public void setPullRetryDelay(long pullRetryDelay) {
		this.pullRetryDelay = pullRetryDelay;
	}

	public Parser getParser() {
		return parser;
	}

	public void setParser(Parser parser) {
		this.parser = parser;
	}

	public PageFetcher getPageFetcher() {
		return pageFetcher;
	}

	public void setPageFetcher(PageFetcher pageFetcher) {
		this.pageFetcher = pageFetcher;
	}

	public RobotstxtServer getRobotsTxtServer() {
		return robotsTxtServer;
	}

	public void setRobotsTxtServer(RobotstxtServer robotsTxtServer) {
		this.robotsTxtServer = robotsTxtServer;
	}

	public Frontier getFrontier() {
		return frontier;
	}

	public void setFrontier(Frontier frontier) {
		this.frontier = frontier;
	}

	/**
	 * This function is called just before starting the crawl by this crawler
	 * instance. It can be used for setting up the data structures or
	 * initializations needed by this crawler instance.
	 */
	public abstract void onStart();

	/**
	 * This function is called just before the termination of the current
	 * crawler instance. It can be used for persisting in-memory data or other
	 * finalization tasks.
	 */
	public abstract void onBeforeExit();

	public void run() {
		onStart();
		while (true) {
			List<CrawlUrl> localQueue = frontier.pull(pullSize);
			if (localQueue.size() == 0) {
				try {
					Thread.sleep(pullRetryDelay);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			} else {
				for (CrawlUrl cUrl : localQueue) {
					try {
						CrawlResult result = processPage(cUrl);
						cUrl.setResult(result);
					} finally {
						crawlHistory.add(localQueue);
					}
				}
			}
		}
	}

	/**
	 * This function is called once the header of a page is fetched. It can be
	 * overwritten by sub-classes to perform custom logic for different status
	 * codes. For example, 404 pages can be logged, etc.
	 * 
	 * @param crawlUrl
	 * @param statusCode
	 * @param statusDescription
	 */
	protected abstract void handlePageStatusCode(
			CrawlUrl crawlUrl, 
			final int statusCode, 
			final String statusDescription);

	/**
	 * This function is called if the content of a url could not be fetched.
	 * 
	 * @param crawlUrl
	 */
	protected abstract void onContentFetchError(final CrawlUrl crawlUrl, final Exception e);

	/**
	 * This function is called if there has been an error in parsing the
	 * content.
	 * 
	 * @param crawlUrl
	 */
	protected abstract void onParseError(CrawlUrl crawlUrl);

	/**
	 * Classes that extends WebCrawler can overwrite this function to tell the
	 * crawler whether the given url should be crawled or not. The following
	 * implementation indicates that all urls should be included in the crawl.
	 * 
	 * @param url
	 *            the url which we are interested to know whether it should be
	 *            included in the crawl or not.
	 * @return if the url should be included in the crawl it returns true,
	 *         otherwise false is returned.
	 */
	public boolean shouldVisit(CrawlUrl url) {
		return true;
	}

	/**
	 * Classes that extends WebCrawler can overwrite this function to process
	 * the content of the fetched and parsed page.
	 * 
	 * @param page
	 *            the page object that is just fetched and parsed.
	 */
	public void visit(Page page) {
		// Do nothing by default
		// Sub-classed can override this to add their custom functionality
	}

	private CrawlResult processPage(CrawlUrl cUrl) {
		
		if (!robotsTxtServer.allows(cUrl.getUrl())) {
			return new CrawlResult(StatusCode.robotsTxtExcluded);
		}
				
		if (!shouldVisit(cUrl)) {
			return new CrawlResult(StatusCode.shouldNotVisit);
		}
		
		PageFetchResult fetchResult = null;
		
		cUrl.setCrawlStartDate(System.currentTimeMillis());
		fetchResult = pageFetcher.fetchHeader(cUrl);
		int statusCode = fetchResult.getStatusCode();

		final String statusDesc = CustomFetchStatus.getStatusDescription(statusCode);
		handlePageStatusCode(
				cUrl, 
				statusCode, 
				statusDesc);

		if (CustomFetchStatus.isCustomCode(statusCode)) {
			return new CrawlResult(StatusCode.fetchError, statusDesc);
		}

		cUrl.setHttpCode(statusCode);

		switch(statusCode) {
		case HttpStatus.SC_OK:
			break;
		case HttpStatus.SC_MOVED_PERMANENTLY:
		case HttpStatus.SC_MOVED_TEMPORARILY:
			return processRedirection(fetchResult, cUrl, true);

		}

		if (!cUrl.getUrl().equals(fetchResult.getFetchedUrl())) {
			// Server or client side redirection happened
			return processRedirection(fetchResult, cUrl, false);
		}

		Page page = new Page(cUrl);

		try {
			fetchResult.fetchContent(page);
		} catch (final Exception e) {
			LOGGER.error("Error during content fetch", e);
			onContentFetchError(cUrl, e);
			return new CrawlResult(
					StatusCode.fetchError, 
					e.getClass().getCanonicalName() + " " + e.getMessage());
		}

		if (!parser.parse(page, cUrl.getUrl())) {
			onParseError(cUrl); // FIXME more detais perhaps?
			return new CrawlResult(StatusCode.failedToParse);
		}

		// We push outlinks only if we have not reached max depth
		// FIXME optimization do not parse HTML if max depth reached
		int maxCrawlDepth = crawlConfig.getMaxDepthOfCrawling();
		if (Type.html.equals(page.getPayloadType())
				&& (maxCrawlDepth == -1 || cUrl.getDepth() < maxCrawlDepth)) {
			List<CrawlUrl> outLinks = page.getOutgoingUrls();
			for (CrawlUrl outLink : outLinks) {
				outLink.setParentUrl(cUrl.getUrl()); // FIXME should it be here?
				outLink.setDepth(cUrl.getDepth() + 1); // FIXME should it be here?
			}				
			frontier.push(outLinks);
		}
		
		try {
			visit(page);
		} catch (final Exception e) {
			LOGGER.error("Exception visiting page method.", e);
			return new CrawlResult(
					StatusCode.visitError, 
					e.getClass().getCanonicalName() + " " + e.getMessage());
		}

		return new CrawlResult(StatusCode.successful);
	}

	protected CrawlResult processRedirection(
			final PageFetchResult fetchResult, 
			final CrawlUrl cUrl,
			boolean isHttpRedirection) {

		StatusCode ok = (isHttpRedirection ? StatusCode.httpRedirect : StatusCode.redirect);
		StatusCode rnf = (isHttpRedirection ? StatusCode.httpRedirectNotFollowed
				: StatusCode.redirectNotFollowed);
		
		String movedToUrl = fetchResult.getMovedToUrl();

		if (!crawlConfig.isFollowRedirects()) {
			return new CrawlResult(rnf, movedToUrl);
		}
		
		CrawlUrl redirectUrl = new CrawlUrl(movedToUrl, cUrl.getCrawlId());
		redirectUrl.setParentUrl(cUrl.getParentUrl());
		redirectUrl.setDepth(cUrl.getDepth());
		redirectUrl.setAnchor(cUrl.getAnchor());

		frontier.push(redirectUrl);
		return new CrawlResult(ok, movedToUrl);
	}

}