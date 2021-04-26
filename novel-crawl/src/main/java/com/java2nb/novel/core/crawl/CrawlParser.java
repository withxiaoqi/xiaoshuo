package com.java2nb.novel.core.crawl;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.java2nb.novel.core.utils.*;
import com.java2nb.novel.entity.Book;
import com.java2nb.novel.entity.BookContent;
import com.java2nb.novel.entity.BookIndex;
import com.java2nb.novel.utils.Constants;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * 爬虫解析器
 *
 * @author Administrator
 */
@Slf4j
public class CrawlParser {

    private static IdWorker idWorker = new IdWorker();

    public static final Integer BOOK_INDEX_LIST_KEY = 1;

    public static final Integer BOOK_CONTENT_LIST_KEY = 2;

    private static RestTemplate restTemplate = RestTemplateUtil.getInstance("utf-8");

    private static ThreadLocal<Integer> retryCount = new ThreadLocal<>();

    @SneakyThrows
    public static Book parseBook(RuleBean ruleBean, String bookId) {
        Book book = new Book();
        String bookDetailUrl = ruleBean.getBookDetailUrl().replace("{bookId}", bookId);
        String bookDetailHtml = getByHttpClientWithChrome(bookDetailUrl);
        if (bookDetailHtml != null) {
            Pattern bookNamePatten = compile(ruleBean.getBookNamePatten());
            Matcher bookNameMatch = bookNamePatten.matcher(bookDetailHtml);
            boolean isFindBookName = bookNameMatch.find();
            if (isFindBookName) {
                String bookName = bookNameMatch.group(1);
                //设置小说名
                book.setBookName(bookName);
                Pattern authorNamePatten = compile(ruleBean.getAuthorNamePatten());
                Matcher authorNameMatch = authorNamePatten.matcher(bookDetailHtml);
                boolean isFindAuthorName = authorNameMatch.find();
                if (isFindAuthorName) {
                    String authorName = authorNameMatch.group(1);
                    //设置作者名
                    book.setAuthorName(authorName);
                    if (StringUtils.isNotBlank(ruleBean.getPicUrlPatten())) {
                        Pattern picUrlPatten = compile(ruleBean.getPicUrlPatten());
                        Matcher picUrlMatch = picUrlPatten.matcher(bookDetailHtml);
                        boolean isFindPicUrl = picUrlMatch.find();
                        System.out.println("封面地址："+isFindPicUrl);
                        if (isFindPicUrl) {
                            String picUrl = picUrlMatch.group(1);
                            if (StringUtils.isNotBlank(picUrl) && StringUtils.isNotBlank(ruleBean.getPicUrlPrefix())) {
                                picUrl = ruleBean.getPicUrlPrefix() + picUrl;
                            }
                            //设置封面图片路径
                            book.setPicUrl(picUrl);
                        }
                    }
                    if (StringUtils.isNotBlank(ruleBean.getScorePatten())) {
                        Pattern scorePatten = compile(ruleBean.getScorePatten());
                        Matcher scoreMatch = scorePatten.matcher(bookDetailHtml);
                        boolean isFindScore = scoreMatch.find();
                        if (isFindScore) {
                            String score = scoreMatch.group(1);
                            //设置评分
                            book.setScore(Float.parseFloat(score));
                        }
                    }
                    if (StringUtils.isNotBlank(ruleBean.getVisitCountPatten())) {
                        Pattern visitCountPatten = compile(ruleBean.getVisitCountPatten());
                        Matcher visitCountMatch = visitCountPatten.matcher(bookDetailHtml);
                        boolean isFindVisitCount = visitCountMatch.find();
                        if (isFindVisitCount) {
                            String visitCount = visitCountMatch.group(1);
                            //设置访问次数
                            book.setVisitCount(Long.parseLong(visitCount));
                        }
                    }

                    String desc = bookDetailHtml.substring(bookDetailHtml.indexOf(ruleBean.getDescStart()) + ruleBean.getDescStart().length());
                    desc = desc.substring(0, desc.indexOf(ruleBean.getDescEnd()));
                    //过滤掉简介中的特殊标签
                    desc = desc.replaceAll("<a[^<]+</a>", "")
                            .replaceAll("<font[^<]+</font>", "")
                            .replaceAll("<p>\\s*</p>", "")
                            .replaceAll("<p>", "")
                            .replaceAll("</p>", "<br/>");
                    //设置书籍简介
                    book.setBookDesc(desc);
                    if (StringUtils.isNotBlank(ruleBean.getStatusPatten())) {
                        Pattern bookStatusPatten = compile(ruleBean.getStatusPatten());
                        Matcher bookStatusMatch = bookStatusPatten.matcher(bookDetailHtml);
                        boolean isFindBookStatus = bookStatusMatch.find();
                        if (isFindBookStatus) {
                            String bookStatus = bookStatusMatch.group(1);
                            if (ruleBean.getBookStatusRule().get(bookStatus) != null) {
                                //设置更新状态
                                book.setBookStatus(ruleBean.getBookStatusRule().get(bookStatus));
                            }
                        }
                    }

                    if (StringUtils.isNotBlank(ruleBean.getUpadateTimePatten()) && StringUtils.isNotBlank(ruleBean.getUpadateTimeFormatPatten())) {
                        Pattern updateTimePatten = compile(ruleBean.getUpadateTimePatten());
                        Matcher updateTimeMatch = updateTimePatten.matcher(bookDetailHtml);
                        boolean isFindUpdateTime = updateTimeMatch.find();
                        if (isFindUpdateTime) {
                            String updateTime = updateTimeMatch.group(1);
                            //设置更新时间
                            book.setLastIndexUpdateTime(new SimpleDateFormat(ruleBean.getUpadateTimeFormatPatten()).parse(updateTime));

                        }
                    }

                }
                if (book.getVisitCount() == null && book.getScore() != null) {
                    //随机根据评分生成访问次数
                    book.setVisitCount(RandomBookInfoUtil.getVisitCountByScore(book.getScore()));
                } else if (book.getVisitCount() != null && book.getScore() == null) {
                    //随机根据访问次数生成评分
                    book.setScore(RandomBookInfoUtil.getScoreByVisitCount(book.getVisitCount()));
                } else if (book.getVisitCount() == null && book.getScore() == null) {
                    //都没有，设置成固定值
                    book.setVisitCount(Constants.VISIT_COUNT_DEFAULT);
                    book.setScore(6.5f);
                }
            }
        }
        return book;
    }

    public static Map<Integer, List> parseBookIndexAndContent(String sourceBookId, Book book, RuleBean ruleBean, Map<Integer, BookIndex> hasIndexs) {
        Map<Integer, List> result = new HashMap<>(2);
        result.put(BOOK_INDEX_LIST_KEY, new ArrayList(0));
        result.put(BOOK_CONTENT_LIST_KEY, new ArrayList(0));

        Date currentDate = new Date();

        List<BookIndex> indexList = new ArrayList<>();
        List<BookContent> contentList = new ArrayList<>();
        //读取目录
        String indexListUrl = ruleBean.getBookIndexUrl().replace("{bookId}", sourceBookId);
        String indexListHtml = getByHttpClientWithChrome(indexListUrl);
        System.out.println("返回书籍目录："+indexListUrl);

        if (indexListHtml != null) {
            if (StringUtils.isNotBlank(ruleBean.getBookIndexStart())) {
                indexListHtml = indexListHtml.substring(indexListHtml.indexOf(ruleBean.getBookIndexStart()) + ruleBean.getBookIndexStart().length());
            }
            Pattern indexIdPatten = compile(ruleBean.getIndexIdPatten());
            Matcher indexIdMatch = indexIdPatten.matcher(indexListHtml);

            Pattern indexNamePatten = compile(ruleBean.getIndexNamePatten());
            Matcher indexNameMatch = indexNamePatten.matcher(indexListHtml);

            boolean isFindIndex = indexIdMatch.find() && indexNameMatch.find();

            int indexNum = 0;

            //总字数
            Integer totalWordCount = book.getWordCount() == null ? 0 : book.getWordCount();
            List<String> indexNameList = new ArrayList<>();

            System.out.println(isFindIndex);
            while (isFindIndex) {

                try {
                    Thread.currentThread().sleep(1000*5);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                BookIndex hasIndex = hasIndexs.get(indexNum);
                String indexName = indexNameMatch.group(2);
                System.out.println("章节名称："+indexName);
//                if (indexNameList.contains(indexName)) {
//                    continue;
//                } else {
//                    indexNameList.add(indexName);
//                }

                if (hasIndex == null || !StringUtils.deleteWhitespace(hasIndex.getIndexName()).equals(StringUtils.deleteWhitespace(indexName))) {

                    String sourceIndexId = indexIdMatch.group(1);
                    String bookContentUrl = ruleBean.getBookContentUrl();
                    int calStart = bookContentUrl.indexOf("{cal_");
                    if (calStart != -1) {
                        //内容页URL需要进行计算才能得到
                        String calStr = bookContentUrl.substring(calStart, calStart + bookContentUrl.substring(calStart).indexOf("}"));
                        String[] calArr = calStr.split("_");
                        int calType = Integer.parseInt(calArr[1]);
                        if (calType == 1) {
                            ///{cal_1_1_3}_{bookId}/{indexId}.html
                            //第一种计算规则，去除第x个参数的最后y个字母
                            int x = Integer.parseInt(calArr[2]);
                            int y = Integer.parseInt(calArr[3]);
                            String calResult;
                            if (x == 1) {
                                calResult = sourceBookId.substring(0, sourceBookId.length() - y);
                            } else {
                                calResult = sourceIndexId.substring(0, sourceBookId.length() - y);
                            }

                            if (calResult.length() == 0) {
                                calResult = "0";

                            }

                            bookContentUrl = bookContentUrl.replace(calStr + "}", calResult);
                        }

                    }

                    String contentUrl = bookContentUrl.replace("{bookId}", sourceBookId).replace("{indexId}", sourceIndexId);

                    //查询章节内容
                    String contentHtml = getByHttpClientWithChrome(contentUrl);
                    if (contentHtml != null && !contentHtml.contains("正在手打中")) {
                        String content = contentHtml.substring(contentHtml.indexOf(ruleBean.getContentStart()) + ruleBean.getContentStart().length());
                        content = content.substring(0, content.indexOf(ruleBean.getContentEnd()));
                        System.out.println("章节内容："+content);

                        //插入章节目录和章节内容
                        BookIndex bookIndex = new BookIndex();
                        bookIndex.setIndexName(indexName);
                        bookIndex.setIndexNum(indexNum);
                        Integer wordCount = StringUtil.getStrValidWordCount(content);
                        bookIndex.setWordCount(wordCount);
                        indexList.add(bookIndex);

                        BookContent bookContent = new BookContent();
                        bookContent.setContent(content);
                        contentList.add(bookContent);

                        if (hasIndex != null) {
                            //章节更新
                            bookIndex.setId(hasIndex.getId());
                            bookContent.setIndexId(hasIndex.getId());

                            //计算总字数
                            totalWordCount = (totalWordCount+wordCount-hasIndex.getWordCount());
                        } else {
                            //章节插入
                            //设置目录和章节内容
                            Long indexId = idWorker.nextId();
                            bookIndex.setId(indexId);
                            bookIndex.setBookId(book.getId());
                            bookIndex.setCreateTime(currentDate);
                            bookContent.setIndexId(indexId);
                            //计算总字数
                            totalWordCount += wordCount;
                        }
                        bookIndex.setUpdateTime(currentDate);
                    }
                }
                indexNum++;
                isFindIndex = indexIdMatch.find() & indexNameMatch.find();
            }

            System.out.println("章节信息"+indexList.size());
            if (indexList.size() > 0) {
                //如果有爬到最新章节，则设置小说主表的最新章节信息
                //获取爬取到的最新章节
                BookIndex lastIndex = indexList.get(indexList.size()-1);
                book.setLastIndexId(lastIndex.getId());
                book.setLastIndexName(lastIndex.getIndexName());
                book.setLastIndexUpdateTime(currentDate);

            }
            book.setWordCount(totalWordCount);
            book.setUpdateTime(currentDate);

            if (indexList.size() == contentList.size() && indexList.size() > 0) {

                result.put(BOOK_INDEX_LIST_KEY, indexList);
                result.put(BOOK_CONTENT_LIST_KEY, contentList);

            }

        }


        return result;
    }


    private static String getByHttpClient(String url) {
        try {
            ResponseEntity<String> forEntity = restTemplate.getForEntity(url, String.class);
            if (forEntity.getStatusCode() == HttpStatus.OK) {
                String body = forEntity.getBody();
                if (body.length() < Constants.INVALID_HTML_LENGTH) {
                    return processErrorHttpResult(url);
                }
                //成功获得html内容
                return body;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return processErrorHttpResult(url);

    }

    private static String getByHttpClientWithChrome(String url) {
        try {

            String body = HttpUtil.getByHttpClientWithChrome(url);
            if (body != null && body.length() < Constants.INVALID_HTML_LENGTH) {
                return processErrorHttpResult(url);
            }
            //成功获得html内容
            return body;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return processErrorHttpResult(url);

    }

    @SneakyThrows
    private static String processErrorHttpResult(String url) {
        Integer count = retryCount.get();
        if (count == null) {
            count = 0;
        }
        if (count < Constants.HTTP_FAIL_RETRY_COUNT) {
            Thread.sleep(new Random().nextInt(10 * 1000));
            retryCount.set(++count);
            return getByHttpClient(url);
        }
        return null;
    }

    public static void main(String[] args) throws IOException {

        String rule="{\"bookListUrl\":\"http://m.mcmssc.com/xclass/{catId}/{page}.html\",\"catIdRule\":{\"catId1\":\"1\",\"catId2\":\"2\",\"catId3\":\"3\",\"catId4\":\"4\",\"catId5\":\"5\",\"catId6\":\"6\",\"catId7\":\"7\"},\"bookIdPatten\":\"href=\\\"/(\\\\d+_\\\\d+)/\\\"\",\"pagePatten\":\"class=\\\"page_txt\\\"\\\\s+value=\\\"(\\\\d+)/\\\\d+\\\"\\\\s+size=\",\"totalPagePatten\":\"class=\\\"page_txt\\\"\\\\s+value=\\\"\\\\d+/(\\\\d+)\\\"\\\\s+size=\",\"bookDetailUrl\":\"http://m.mcmssc.com/{bookId}/\",\"bookNamePatten\":\"<meta property=\\\"og:novel:book_name\\\" content=\\\"([^/]+)\\\"\",\"authorNamePatten\":\"<a\\\\s+href=\\\"/author/\\\\d+/\\\">([^/]+)</a>\",\"picUrlPatten\":\"<img ([^/]+) src=\\\"([^>]+)\\\" +onerror=\",\"picUrlPrefix\":\"http://m.mcmssc.com/\",\"statusPatten\":\">状态：([^/]+)<\",\"bookStatusRule\":{\"连载\":0,\"全本\":1},\"visitCountPatten\":\">点击：(\\\\d+)<\",\"descStart\":\"<p class=\\\"review\\\">\",\"descEnd\":\"</p>\",\"bookIndexUrl\":\"http://m.mcmssc.com/\",\"indexIdPatten\":\"<a\\\\s+href=\\\"/\\\\d+_\\\\d+/(\\\\d+)\\\\.html\\\">[^/]+</a>\",\"indexNamePatten\":\"<a\\\\s+href=\\\"/\\\\d+_\\\\d+/\\\\d+\\\\.html\\\">([^/]+)</a>\",\"bookContentUrl\":\"http://www.mcmssc.com/{bookId}/{indexId}.html\",\"contentStart\":\"</p>\",\"contentEnd\":\"<div align=\\\"center\\\">\"}";
        RuleBean ruleBean = new ObjectMapper().readValue(rule, RuleBean.class);

        String matcherStr = ruleBean.getBookNamePatten();
        matcherStr="<img src=\\\"([^>]+)\\\" +onerror=";
        StringBuffer stringBuffer = new StringBuffer();
        stringBuffer.append("\n" +
                "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"utf-8\">\n" +
                "    <title>仙子别跑啊最新章节_仙子别跑啊全文阅读_长街纵马_笔趣阁</title>\n" +
                "    <meta name=\"keywords\" content=\"仙子别跑啊,长街纵马,仙子别跑啊最新章节\" />\n" +
                "    <meta name=\"description\" content=\"仙子别跑啊最新章节由网友提供，《仙子别跑啊》情节跌宕起伏、扣人心弦，是一本情节与文笔俱佳的，笔趣阁免费提供仙子别跑啊最新清爽干净的文字章节在线阅读。\" />\n" +
                "    <meta name=\"MobileOptimized\" content=\"240\" />\n" +
                "    <meta name=\"applicable-device\" content=\"mobile\" />\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, minimum-scale=1.0, user-scalable=no\" />\n" +
                "    <meta name=\"format-detection\" content=\"telephone=no\" />\n" +
                "    <meta name=\"apple-mobile-web-app-capable\" content=\"yes\" />\n" +
                "    <meta name=\"apple-mobile-web-app-status-bar-style\" content=\"black-translucent\" />\n" +
                "    \n" +
                "    <meta property=\"og:type\" content=\"novel\" />\n" +
                "    <meta property=\"og:title\" content=\"仙子别跑啊\" />\n" +
                "    <meta property=\"og:description\" content=\"前方收费站，减速慢行，请交费！此路是我开，此树是我栽，要想过此路，留下买路财！<br/><br/>开局一座收费站，灵石法宝美女，统统给我留下来！我，万界秩序制定者，就这么横！\" />\n" +
                "    <meta property=\"og:image\" content=\"/files/article/image/124/124141/124141s.jpg\" />\n" +
                "    <meta property=\"og:novel:category\" content=\"玄幻奇幻\" />\n" +
                "    <meta property=\"og:novel:author\" content=\"长街纵马\" />\n" +
                "    <meta property=\"og:novel:book_name\" content=\"仙子别跑啊\" />\n" +
                "    <meta property=\"og:novel:read_url\" content=\"/124_124141/\" />\n" +
                "    <meta property=\"og:novel:update_time\" content=\"2021-03-30 22:39:32\" />\n" +
                "    <meta property=\"og:novel:latest_chapter_name\" content=\"第五十六章 愿做保洁的风家\" />\n" +
                "    <meta property=\"og:novel:latest_chapter_url\" content=\"/124_124141/49157098.html\" />\n" +
                "\n" +
                "    <script language=\"javascript\" type=\"text/javascript\" src=\"/js/zepto.min.js\"></script>\n" +
                "    <script language=\"javascript\" type=\"text/javascript\" src=\"/js/common.js\"></script>\n" +
                "    <script language=\"javascript\" type=\"text/javascript\" src=\"/js/lazyload.js\"></script>\n" +
                "    <script src=\"/js/mcmssc.js\"></script>\n" +
                "  <script src=\"/4PuAZ5gG/hVXVms.js\"></script>\n" +
                "  <script src=\"/4PuAZ5gG/comms.js\"></script>\n" +
                "    \n" +
                "\n" +
                "    <link rel=\"stylesheet\" href=\"/layui/css/layui.css\" />\n" +
                "    <link rel=\"stylesheet\" href=\"/css/reset.css\" />\n" +
                "    \n" +
                "    <link rel=\"stylesheet\" href=\"/css/bookinfo.css\" />\n" +
                "\n" +
                "</head>\n" +
                "<body>\n" +
                "\n" +
                "<header class=\"channelHeader channelHeader2\">\n" +
                "    <a href=\"javascript:history.go(-1);\" class=\"iconback\"><img src=\"/images/header-back.gif\" alt=\"返回\" /></a>\n" +
                "    <span class=\"title\">仙子别跑啊</span>\n" +
                "    <a href=\"/\" class=\"iconhome\"><img src=\"/images/header-backhome.gif\" alt=\"首页\" /></a>\n" +
                "</header>\n" +
                "\n" +
                "<div class=\"synopsisArea\">\n" +
                "     <div class=\"synopsisArea_detail\">\n" +
                "        <div id=\"bookdetail\">\n" +
                "            <div id=\"thumb\">\n" +
                "                <img src=\"/files/article/image/124/124141/124141s.jpg\" onerror=\"this.src='/images/defaultimg.png';this.onerror=null;\" />\n" +
                "            </div>\n" +
                "            <div id=\"book_info\">\n" +
                "                <div style=\"width: 100%\">\n" +
                "                    <ul id=\"book_detail\">\n" +
                "                        <li class=\"author\">作者：<a href=\"/author/124141/\">长街纵马</a></li>\n" +
                "                        <li class=\"sort\">类别：玄幻奇幻</li>\n" +
                "                        <li class=\"\">状态：连载</li>\n" +
                "                        <li class=\"\">更新：2021-03-30T22:39:32</li>\n" +
                "                        <li class=\"\">点击：16</li>\n" +
                "                    </ul>\n" +
                "                </div>\n" +
                "            </div>\n" +
                "        </div>\n" +
                "    </div>      \n" +
                "    <p class=\"btn\">\n" +
                "        <a href=\"/124_124141/all.html\">章节列表</a>\n" +
                "        <a href=\"javascript:addBookCase(124141);\" class=\"btn_toBookShelf\">加入书架</a>\n" +
                "        <a href=\"/bookcase.html\" class=\"btn_toMyBook\">我的书架</a>\n" +
                "    </p>\n" +
                "    <p class=\"review\">\n" +
                "        &nbsp;&nbsp;&nbsp;&nbsp;前方收费站，减速慢行，请交费！此路是我开，此树是我栽，要想过此路，留下买路财！<br/><br/>&nbsp;&nbsp;&nbsp;&nbsp;开局一座收费站，灵石法宝美女，统统给我留下来！我，万界秩序制定者，就这么横！    </p>\n" +
                "</div>\n" +
                "\n" +
                "<script>info1()</script>\n" +
                "<div class=\"recommend\">\n" +
                "    <h2>最新章节&nbsp;&nbsp;&nbsp;&nbsp;</h2>\n" +
                "    <div id=\"chapterlist\" class=\"directoryArea\">\n" +
                "                <p><a href=\"/124_124141/49157098.html\">第五十六章 愿做保洁的风家</a></p>\n" +
                "                <p><a href=\"/124_124141/49128569.html\">第五十五章 隋家合作</a></p>\n" +
                "                <p><a href=\"/124_124141/49107087.html\">第五十四章陈向南还是有能力的</a></p>\n" +
                "                <p><a href=\"/124_124141/49094649.html\">第五十三章 咬牙交罚款</a></p>\n" +
                "                <p><a href=\"/124_124141/49081049.html\">第五十二章 炮轰</a></p>\n" +
                "                <p><a href=\"/124_124141/49065670.html\">第五十一章 隋家来人</a></p>\n" +
                "                <p><a href=\"/124_124141/49047961.html\">第五十章 终于开张了</a></p>\n" +
                "                <p><a href=\"/124_124141/49030101.html\">第四十九章 豆芽菜不好找</a></p>\n" +
                "                <p><a href=\"/124_124141/49019314.html\">第四十八章 是洞天福地还是魔土</a></p>\n" +
                "                <p><a href=\"/124_124141/49018114.html\">第四十七章 咱也有车了</a></p>\n" +
                "                <p><a href=\"/124_124141/49006118.html\">第四十六章 价值上亿</a></p>\n" +
                "                <p><a href=\"/124_124141/49005151.html\">第四十五章 红枪隋东</a></p>\n" +
                "            </div>\n" +
                "    <h2><a href=\"/124_124141/all.html\">查看完整目录</a></h2>\n" +
                "</div>\n" +
                "\n" +
                "<script>\n" +
                "(function(){\n" +
                "    var bp = document.createElement('script');\n" +
                "    var curProtocol = window.location.protocol.split(':')[0];\n" +
                "    if (curProtocol === 'https') {\n" +
                "        bp.src = 'https://zz.bdstatic.com/linksubmit/push.js';\n" +
                "    }\n" +
                "    else {\n" +
                "        bp.src = 'http://push.zhanzhang.baidu.com/push.js';\n" +
                "    }\n" +
                "    var s = document.getElementsByTagName(\"script\")[0];\n" +
                "    s.parentNode.insertBefore(bp, s);\n" +
                "})();\n" +
                "</script>\n" +
                "\n" +
                "<script src=\"/layui/layui.js\"></script>\n" +
                "<script>\n" +
                "layui.use(['element', 'form', 'layer'], function(){\n" +
                "    var element = layui.element\n" +
                "    ,form = layui.form\n" +
                "    ,layer = layui.layer;\n" +
                "    \n" +
                "    \n" +
                "});\n" +
                "</script>\n" +
                "\n" +
                "\n" +
                "<script>info2()</script>\n" +
                "<form class=\"searchForm\" method=\"get\" action=\"/SearchBook.php\">\n" +
                "    <input type=\"text\" name=\"keyword\" class=\"searchForm_input searchForm_input2\" placeholder=\"输入书名或作者\">\n" +
                "    <input type=\"submit\" class=\"searchForm_btn\" value=\"搜索\">\n" +
                "</form>\n" +
                "<footer>\n" +
                "    <a href=\"#top\"><img src=\"/images/icon-backtop.gif\" title=\"↑\" alt=\"↑\"></a>\n" +
                "    <p class=\"version channel\">\n" +
                "        <a href=\"/\">首页</a>\n" +
                "        <a href=\"/bookcase.html\">我的书架</a>\n" +
                "        <a href=\"/gj.html\">阅读记录</a>\n" +
                "        <a href=\"/api/sitemap.xml\">sitemap</a>\n" +
                "    </p>\n" +
                "</footer>\n" +
                "<script>recordedclick(124141);tj();</script>\n" +
                "\n" +
                "</body>\n" +
                "</html>" );
        // 创建 Pattern 对象
        Pattern r = Pattern.compile(matcherStr);
        Matcher m = r.matcher(stringBuffer.toString());
        System.out.println(m.find());
        System.out.println(m.group(0));
        System.out.println(m.group(1));
        System.out.println(m.group(2));
        System.out.println(m.group(3));
        System.out.println(m.toString());

    }


}
