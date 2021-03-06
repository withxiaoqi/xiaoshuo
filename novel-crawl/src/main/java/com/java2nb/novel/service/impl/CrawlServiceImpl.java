package com.java2nb.novel.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.java2nb.novel.core.bean.PageBean;
import com.java2nb.novel.core.cache.CacheKey;
import com.java2nb.novel.core.cache.CacheService;
import com.java2nb.novel.core.crawl.CrawlParser;
import com.java2nb.novel.core.crawl.RuleBean;
import com.java2nb.novel.core.enums.ResponseStatus;
import com.java2nb.novel.core.exception.BusinessException;
import com.java2nb.novel.core.utils.BeanUtil;
import com.java2nb.novel.core.utils.IdWorker;
import com.java2nb.novel.core.utils.SpringUtil;
import com.java2nb.novel.core.utils.ThreadUtil;
import com.java2nb.novel.entity.*;
import com.java2nb.novel.entity.CrawlSource;
import com.java2nb.novel.mapper.*;
import com.java2nb.novel.service.BookService;
import com.java2nb.novel.service.CrawlService;
import com.java2nb.novel.vo.CrawlSingleTaskVO;
import com.java2nb.novel.vo.CrawlSourceVO;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.mybatis.dynamic.sql.render.RenderingStrategies;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.java2nb.novel.core.utils.HttpUtil.getByHttpClient;
import static com.java2nb.novel.core.utils.HttpUtil.getByHttpClientWithChrome;
import static com.java2nb.novel.mapper.BookDynamicSqlSupport.crawlBookId;
import static com.java2nb.novel.mapper.BookDynamicSqlSupport.crawlSourceId;
import static com.java2nb.novel.mapper.CrawlSourceDynamicSqlSupport.*;
import static org.mybatis.dynamic.sql.SqlBuilder.*;
import static org.mybatis.dynamic.sql.select.SelectDSL.select;

/**
 * @author Administrator
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrawlServiceImpl implements CrawlService {


    private final CrawlSourceMapper crawlSourceMapper;

    private final CrawlSingleTaskMapper crawlSingleTaskMapper;

    private final BookService bookService;


    private final CacheService cacheService;


    @Override
    public void addCrawlSource(CrawlSource source) {
        Date currentDate = new Date();
        source.setCreateTime(currentDate);
        source.setUpdateTime(currentDate);
        crawlSourceMapper.insertSelective(source);

    }

    @Override
    public PageBean<CrawlSource> listCrawlByPage(int page, int pageSize) {
        PageHelper.startPage(page, pageSize);
        SelectStatementProvider render = select(id, sourceName, sourceStatus, createTime, updateTime)
                .from(crawlSource)
                .orderBy(updateTime)
                .build()
                .render(RenderingStrategies.MYBATIS3);
        List<CrawlSource> crawlSources = crawlSourceMapper.selectMany(render);
        PageBean<CrawlSource> pageBean = new PageBean<>(crawlSources);
        pageBean.setList(BeanUtil.copyList(crawlSources, CrawlSourceVO.class));
        return pageBean;
    }

    @SneakyThrows
    @Override
    public void openOrCloseCrawl(Integer sourceId, Byte sourceStatus) {

        //????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        //?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        // ?????????????????????????????????????????????????????????????????????????????????runningCrawlThread???
        if (sourceStatus == (byte) 0) {
            //??????,?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            SpringUtil.getBean(CrawlService.class).updateCrawlSourceStatus(sourceId, sourceStatus);
            Set<Long> runningCrawlThreadId = (Set<Long>) cacheService.getObject(CacheKey.RUNNING_CRAWL_THREAD_KEY_PREFIX + sourceId);
            if (runningCrawlThreadId != null) {
                for (Long ThreadId : runningCrawlThreadId) {
                    Thread thread = ThreadUtil.findThread(ThreadId);
                    if (thread != null && thread.isAlive()) {
                        thread.interrupt();
                    }
                }
            }


        } else {
            //??????
            //??????????????????????????????
            CrawlSource source = queryCrawlSource(sourceId);
            Byte realSourceStatus = source.getSourceStatus();

            if (realSourceStatus == (byte) 0) {
                //?????????????????????????????????,??????????????????????????????????????????????????????????????????runningCrawlThread???
                SpringUtil.getBean(CrawlService.class).updateCrawlSourceStatus(sourceId, sourceStatus);
                RuleBean ruleBean = new ObjectMapper().readValue(source.getCrawlRule(), RuleBean.class);

                Set<Long> threadIds = new HashSet<>();
                //?????????????????????????????????
                for (int i = 1; i < 8; i++) {
                    final int catId = i;
                    Thread thread = new Thread(() -> {
                        parseBookList(catId, ruleBean, sourceId);
                    });
                    thread.start();
                    //thread????????????????????????
                    threadIds.add(thread.getId());
                }
                cacheService.setObject(CacheKey.RUNNING_CRAWL_THREAD_KEY_PREFIX + sourceId, threadIds);


            }


        }

    }

    @Override
    public CrawlSource queryCrawlSource(Integer sourceId) {
        SelectStatementProvider render = select(CrawlSourceDynamicSqlSupport.sourceStatus, CrawlSourceDynamicSqlSupport.crawlRule)
                .from(crawlSource)
                .where(id, isEqualTo(sourceId))
                .build()
                .render(RenderingStrategies.MYBATIS3);
        return crawlSourceMapper.selectMany(render).get(0);
    }

    @Override
    public void addCrawlSingleTask(CrawlSingleTask singleTask) {

        if(bookService.queryIsExistByBookNameAndAuthorName(singleTask.getBookName(),singleTask.getAuthorName())){
            throw new BusinessException(ResponseStatus.BOOK_EXISTS);

        }
        singleTask.setCreateTime(new Date());
        crawlSingleTaskMapper.insertSelective(singleTask);


    }

    @Override
    public PageBean<CrawlSingleTask> listCrawlSingleTaskByPage(int page, int pageSize) {
        PageHelper.startPage(page, pageSize);
        SelectStatementProvider render = select(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask.allColumns())
                .from(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask)
                .orderBy(CrawlSingleTaskDynamicSqlSupport.createTime.descending())
                .build()
                .render(RenderingStrategies.MYBATIS3);
        List<CrawlSingleTask> crawlSingleTasks = crawlSingleTaskMapper.selectMany(render);
        PageBean<CrawlSingleTask> pageBean = new PageBean<>(crawlSingleTasks);
        pageBean.setList(BeanUtil.copyList(crawlSingleTasks, CrawlSingleTaskVO.class));
        return pageBean;
    }

    @Override
    public void delCrawlSingleTask(Long id) {
        crawlSingleTaskMapper.deleteByPrimaryKey(id);
    }

    @Override
    public CrawlSingleTask getCrawlSingleTask() {

         List<CrawlSingleTask> list = crawlSingleTaskMapper.selectMany(select(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask.allColumns())
                .from(CrawlSingleTaskDynamicSqlSupport.crawlSingleTask)
                .where(CrawlSingleTaskDynamicSqlSupport.taskStatus,isEqualTo((byte)2))
                 .and(CrawlSingleTaskDynamicSqlSupport.excCount,isEqualTo((byte)0))
                 .orderBy(CrawlSingleTaskDynamicSqlSupport.createTime)
                 .limit(1)
                .build()
                .render(RenderingStrategies.MYBATIS3));

         return list.size() > 0 ? list.get(0) : null;
    }

    @Override
    public void updateCrawlSingleTask(CrawlSingleTask task, Byte status) {
        byte excCount = task.getExcCount();
        excCount+=1;
        task.setExcCount(excCount);
        if(status == 1 || excCount == 5){
            //???????????????????????????????????????5????????????????????????????????????????????????
            task.setTaskStatus(status);
        }
        crawlSingleTaskMapper.updateByPrimaryKeySelective(task);

    }

    /**
     * ??????????????????
     */
    @Override
    public void parseBookList(int catId, RuleBean ruleBean, Integer sourceId) {

        //????????????1
        int page = 1;
        int totalPage = page;

        while (page <= totalPage) {
            try {
                if(StringUtils.isNotBlank(ruleBean.getCatIdRule().get("catId" + catId))) {
                    //????????????URL
                    String catBookListUrl = ruleBean.getBookListUrl()
                            .replace("{catId}", ruleBean.getCatIdRule().get("catId" + catId))
                            .replace("{page}", page + "");

                    System.out.println(catBookListUrl);
                    String bookListHtml = getByHttpClientWithChrome(catBookListUrl);
                    if (bookListHtml != null) {
                        Pattern bookIdPatten = Pattern.compile(ruleBean.getBookIdPatten());
                        Matcher bookIdMatcher = bookIdPatten.matcher(bookListHtml);
                        boolean isFindBookId = bookIdMatcher.find();
                        while (isFindBookId) {
                            try {
                                //1.???????????????????????? sleep,???????????? wait,socket ?????? receiver,accept ???????????????
                                //??????????????????InterruptedException??????????????????
                                //2.????????????????????????????????????????????????????????????
                                if(Thread.currentThread().isInterrupted()){
                                    return;
                                }
                                String bookId = bookIdMatcher.group(1);
                                parseBookAndSave(catId, ruleBean, sourceId, bookId);
                            } catch (Exception e) {
                                log.error(e.getMessage(), e);
                            }
                            isFindBookId = bookIdMatcher.find();
                        }

                        Pattern totalPagePatten = Pattern.compile(ruleBean.getTotalPagePatten());
                        Matcher totalPageMatcher = totalPagePatten.matcher(bookListHtml);
                        boolean isFindTotalPage = totalPageMatcher.find();
                        if (isFindTotalPage) {
                            totalPage = Integer.parseInt(totalPageMatcher.group(1));
                            System.out.println("???????????????"+totalPage);
                            //?????????????????????
                            //totalPage = 1;
                        }
                    }
                }
            }catch (Exception e){
                log.error(e.getMessage(),e);
            }

            page += 1;
        }


    }

    @Override
    public boolean parseBookAndSave(int catId, RuleBean ruleBean, Integer sourceId, String bookId) {
        Book book = CrawlParser.parseBook(ruleBean, bookId);
        if(book.getBookName() == null || book.getAuthorName() == null){
            return false;
        }
        //??????????????????????????????????????????????????????
        Book existBook = bookService.queryBookByBookNameAndAuthorName(book.getBookName(), book.getAuthorName());
        //???????????????????????????????????????????????????????????????????????????????????????30?????????????????????????????????
        if (existBook == null) {
            //???????????????????????????
            book.setCatId(catId);
            //????????????ID????????????
            book.setCatName(bookService.queryCatNameByCatId(catId));
            if (catId == 7) {
                //??????
                book.setWorkDirection((byte) 1);
            } else {
                //??????
                book.setWorkDirection((byte) 0);
            }
            book.setCrawlBookId(bookId);
            book.setCrawlSourceId(sourceId);
            book.setCrawlLastTime(new Date());
            book.setId(new IdWorker().nextId());
            //??????????????????
            Map<Integer, List> indexAndContentList = CrawlParser.parseBookIndexAndContent(bookId, book, ruleBean, new HashMap<>(0));

            bookService.saveBookAndIndexAndContent(book, (List<BookIndex>) indexAndContentList.get(CrawlParser.BOOK_INDEX_LIST_KEY), (List<BookContent>) indexAndContentList.get(CrawlParser.BOOK_CONTENT_LIST_KEY));

        } else {
            //????????????????????????????????????
            bookService.updateCrawlProperties(existBook.getId(), sourceId, bookId);
        }
        return true;
    }

    @Override
    public void updateCrawlSourceStatus(Integer sourceId, Byte sourceStatus) {
        CrawlSource source = new CrawlSource();
        source.setId(sourceId);
        source.setSourceStatus(sourceStatus);
        crawlSourceMapper.updateByPrimaryKeySelective(source);
    }

    @Override
    public List<CrawlSource> queryCrawlSourceByStatus(Byte sourceStatus) {
        SelectStatementProvider render = select(CrawlSourceDynamicSqlSupport.id, CrawlSourceDynamicSqlSupport.sourceStatus, CrawlSourceDynamicSqlSupport.crawlRule)
                .from(crawlSource)
                .where(CrawlSourceDynamicSqlSupport.sourceStatus, isEqualTo(sourceStatus))
                .build()
                .render(RenderingStrategies.MYBATIS3);
        return crawlSourceMapper.selectMany(render);
    }

    public static void main(String[] args) {
        String catBookListUrl = "http://m.mcmssc.com/123_123195/";
        String bookListHtml = getByHttpClientWithChrome(catBookListUrl);
        System.out.println(bookListHtml);
    }
}
